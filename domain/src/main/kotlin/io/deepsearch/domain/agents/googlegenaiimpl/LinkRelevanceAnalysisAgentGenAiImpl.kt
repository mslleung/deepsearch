package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.ILinkRelevanceAnalysisAgent
import io.deepsearch.domain.agents.LinkRelevanceAnalysisInput
import io.deepsearch.domain.agents.LinkRelevanceAnalysisOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.ext.toSafeUri
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An agent that analyzes HTML to identify links relevant to a query.
 * Extracts links into a compact structured format (path, display text, section, context)
 * and sends that to the LLM instead of the full page text.
 */
class LinkRelevanceAnalysisAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : ILinkRelevanceAnalysisAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private data class LinkDescriptor(
        val path: String,
        val displayText: String,
        val section: String,
        val context: String
    )

    private data class LinkExtractionResult(
        val pageTitle: String?,
        val pageDescription: String?,
        val links: List<LinkDescriptor>,
        val validRelativePaths: Set<String>,
        val allAbsoluteUrls: Set<String>,
        val pathToAbsoluteUrl: Map<String, String>
    )

    private val relevantLinkSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A relevant link with path, reasoning, and relevance score")
        .properties(
            mapOf(
                "url" to Schema.builder().type("STRING")
                    .description("The relative path of the relevant link (exactly as shown in the source)")
                    .build(),
                "reason" to Schema.builder().type("STRING")
                    .description("Brief explanation of why this link is relevant to the query")
                    .build(),
                "score" to Schema.builder().type("INTEGER")
                    .description("Relevance score from 1-10 (10 = highly relevant, likely to directly answer query; 1 = tangentially related)")
                    .build()
            )
        )
        .required(listOf("url", "reason", "score"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Collection of relevant links identified from the webpage")
        .properties(
            mapOf(
                "links" to Schema.builder().type("ARRAY").items(relevantLinkSchema)
                    .description("List of all relevant links found in the webpage")
                    .build()
            )
        )
        .required(listOf("links"))
        .build()

    @Serializable
    private data class RelevantLinkJson(
        val url: String,
        val reason: String,
        val score: Int
    )

    @Serializable
    private data class LinkAnalysisResponse(
        val links: List<RelevantLinkJson>
    )

    private val systemInstruction = """
        You are a link relevance analysis agent.
        
        Given a list of links extracted from a webpage, analyze which links are relevant to the user's query.
        
        Each link is presented in this format:
        path | display text | page section | surrounding context
        
        Your task is to:
        1. Analyze which links are relevant to the user's query based on the path, display text, page context, and surrounding text
        2. Be very permissive, look for any link that may be relevant directly or indirectly to the user query
        3. Include links that may lead to pages with a high chance to contain linkage to pages related to the query (multi-hop)
        4. Return all relevant links with reasons why they are relevant and a relevance score
        5. The url field must be exactly the path shown in the first column
        
        Scoring Guidelines (1-10):
        - 10: Highly likely to directly answer the query (e.g., pricing page for a pricing query)
        - 7-9: Very relevant, likely contains substantial information
        - 4-6: Moderately relevant, may contain some useful information
        - 1-3: Tangentially related, might lead to relevant content via multiple hops
        
        Return your response in JSON format, example:
        {
          "links": [
            {
              "url": "/pricing",
              "reason": "Official pricing page likely contains pricing details",
              "score": 10
            },
            {
              "url": "/docs/page",
              "reason": "Documentation that may reference pricing or plans",
              "score": 6
            }
          ]
        }
        
        If there are no relevant links, return an empty links array.
    """.trimIndent()

    override suspend fun generate(input: LinkRelevanceAnalysisInput): LinkRelevanceAnalysisOutput {
        logger.debug("Analyzing link relevance for query: '{}'", input.query)

        val extraction = extractLinks(input.html, input.url)
        logger.debug("Extracted {} unique links", extraction.validRelativePaths.size)

        if (extraction.validRelativePaths.isEmpty()) {
            logger.debug("No links found in HTML")

            return LinkRelevanceAnalysisOutput(
                links = emptyList(),
                allEvaluatedUrls = emptySet(),
                tokenUsage = TokenUsageMetrics.empty()
            )
        }

        // Concurrent cross-page dedup: atomically claim URLs via the shared set.
        // Only URLs successfully added (not already present) are included in the prompt.
        val effectiveExtraction = if (input.sharedEvaluatedUrls != null) {
            val newAbsoluteUrls = extraction.allAbsoluteUrls.filter { input.sharedEvaluatedUrls.add(it) }.toSet()
            val filteredLinks = extraction.links.filter { link ->
                val absoluteUrl = extraction.pathToAbsoluteUrl[link.path]
                absoluteUrl != null && absoluteUrl in newAbsoluteUrls
            }
            val filteredPaths = filteredLinks.map { it.path }.toSet()
            val skippedCount = extraction.links.size - filteredLinks.size
            if (skippedCount > 0) {
                logger.debug("Cross-page dedup: {}/{} links already claimed by concurrent calls", skippedCount, extraction.links.size)
            }
            if (filteredLinks.isEmpty()) {
                logger.debug("All links already claimed by concurrent calls, skipping LLM analysis")
                return LinkRelevanceAnalysisOutput(
                    links = emptyList(),
                    allEvaluatedUrls = extraction.allAbsoluteUrls,
                    tokenUsage = TokenUsageMetrics.empty()
                )
            }
            extraction.copy(links = filteredLinks, validRelativePaths = filteredPaths)
        } else {
            extraction
        }

        val userPrompt = buildPrompt(input.query, effectiveExtraction)

        val modelId = ModelIds.GEMINI_3_5_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val links = try {
            val response = withContext(dispatcherProvider.io) {
                retryLlmCall<LinkAnalysisResponse>(this@LinkRelevanceAnalysisAgentGenAiImpl::class.simpleName!!) {
                    val result = client.models.generateContent(
                        modelId,
                        userPrompt,
                        GenerateContentConfig.builder()
                            .responseSchema(outputSchema)
                            .responseMimeType("application/json")
                            .thinkingConfig(
                                ThinkingConfig.builder()
                                    .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                                    .build()
                            )
                            .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                            .build()
                    )

                    result.checkFinishReason()

                    result.usageMetadata().ifPresent { metadata ->
                        tokenUsage = TokenUsageMetrics(
                            modelName = modelId,
                            promptTokens = metadata.promptTokenCount().orElse(0),
                            outputTokens = metadata.candidatesTokenCount().orElse(0),
                            totalTokens = metadata.totalTokenCount().orElse(0)
                        )
                    }

                    result.text() ?: throw RuntimeException("No text response from model")
                }
            }

            val baseUri = input.url.toSafeUri()
            val validatedLinks = response.links.distinctBy { it.url }.mapNotNull { linkJson ->
                if (linkJson.url in effectiveExtraction.validRelativePaths) {
                    val absoluteUrl = baseUri.resolve(linkJson.url).toString()
                    val clampedScore = linkJson.score.coerceIn(1, 10)
                    WebpageLink(
                        url = absoluteUrl,
                        source = LinkSource.LINK_RELEVANCE,
                        reason = linkJson.reason,
                        score = clampedScore
                    )
                } else {
                    logger.warn("LLM returned hallucinated link not found in original HTML: '{}'", linkJson.url)
                    null
                }
            }

            val hallucinatedCount = response.links.size - validatedLinks.size
            if (hallucinatedCount > 0) {
                logger.warn("Filtered out {} hallucinated link(s) from LLM response", hallucinatedCount)
            }

            validatedLinks
        } catch (e: Exception) {
            logger.warn("Failed to parse link analysis response: {}", e.message)
            emptyList()
        }

        logger.debug("Link relevance analysis found {} relevant links", links.size)

        return LinkRelevanceAnalysisOutput(
            links = links,
            allEvaluatedUrls = extraction.allAbsoluteUrls,
            tokenUsage = tokenUsage
        )
    }

    private fun buildPrompt(query: String, extraction: LinkExtractionResult): String {
        return buildString {
            appendLine("Query: $query")
            appendLine()

            extraction.pageTitle?.let { appendLine("Page: $it") }
            extraction.pageDescription?.let { appendLine("Page description: $it") }
            if (extraction.pageTitle != null || extraction.pageDescription != null) {
                appendLine()
            }

            appendLine("Links:")
            for (link in extraction.links) {
                append(link.path)
                append(" | ")
                append(link.displayText)
                append(" | ")
                append(link.section)
                if (link.context.isNotBlank()) {
                    append(" | ")
                    append(link.context)
                }
                appendLine()
            }
        }
    }

    /**
     * Parses HTML, filters anchors to same-host relative paths, and extracts a compact
     * [LinkDescriptor] for each unique link (display text, section, surrounding context).
     */
    private fun extractLinks(rawHtml: String, url: String): LinkExtractionResult {
        val doc = Jsoup.parse(rawHtml)
        val baseUri = url.toSafeUri()
        val baseHost = baseUri.host

        val pageTitle = doc.title().takeIf { it.isNotBlank() }
        val pageDescription = doc.selectFirst("meta[name=description]")
            ?.attr("content")?.takeIf { it.isNotBlank() }

        doc.select(
            "script, style, noscript, template, svg, canvas, meta, link[rel], iframe, object, embed, head"
        ).remove()

        doc.select("form, input, select, textarea, button").remove()

        val validSchemes = setOf("http", "https")
        val validRelativePaths = mutableSetOf<String>()
        val allAbsoluteUrls = mutableSetOf<String>()
        val pathToAbsoluteUrl = mutableMapOf<String, String>()

        // Bulk-remove anchors with non-HTTP schemes and empty/fragment-only hrefs before the main loop
        val nonHttpSchemes = listOf(
            "javascript", "mailto", "tel", "data", "blob", "ftp", "file", "sms", "geo", "market"
        )
        val bulkRemoveSelector = nonHttpSchemes.joinToString(", ") { scheme -> "a[href^='$scheme:']" } +
            ", a[href=''], a[href^='#']"
        val bulkRemoved = doc.select(bulkRemoveSelector)
        val bulkRemovedCount = bulkRemoved.size
        bulkRemoved.remove()
        if (bulkRemovedCount > 0) {
            logger.debug("Bulk-removed {} anchors with non-HTTP schemes or fragment-only hrefs", bulkRemovedCount)
        }

        var offHostRemoved = 0
        var invalidRemoved = 0
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()

            if (href.isBlank()) {
                anchor.remove()
                return@forEach
            }

            try {
                val resolvedUri = baseUri.resolve(href)
                val scheme = resolvedUri.scheme?.lowercase()
                val resolvedHost = resolvedUri.host

                if (scheme !in validSchemes) {
                    invalidRemoved++
                    anchor.remove()
                } else if (resolvedHost != null && !resolvedHost.equals(baseHost, ignoreCase = true)) {
                    offHostRemoved++
                    anchor.remove()
                } else {
                    val absoluteUrl = resolvedUri.toString()
                    val relativePath = buildString {
                        append(resolvedUri.rawPath ?: "/")
                        resolvedUri.rawQuery?.let { append("?").append(it) }
                    }
                    allAbsoluteUrls.add(absoluteUrl)
                    pathToAbsoluteUrl[relativePath] = absoluteUrl
                    anchor.attr("href", relativePath)
                    validRelativePaths.add(relativePath)
                }
            } catch (e: Exception) {
                invalidRemoved++
                anchor.remove()
            }
        }
        if (offHostRemoved > 0 || invalidRemoved > 0) {
            logger.debug("Filtered anchors: {} off-host, {} invalid scheme/href removed", offHostRemoved, invalidRemoved)
        }
        logger.debug("Filtered anchor tags to host: {}", baseHost)

        val seenPaths = mutableSetOf<String>()
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (href.isNotBlank()) {
                if (href in seenPaths) anchor.remove()
                else seenPaths.add(href)
            }
        }
        logger.debug("Removed duplicate links, kept {} unique paths", seenPaths.size)

        val links = doc.select("a[href]").mapNotNull { anchor ->
            val path = anchor.attr("href")
            if (path.isBlank()) return@mapNotNull null

            LinkDescriptor(
                path = path,
                displayText = resolveDisplayText(anchor),
                section = detectSection(anchor),
                context = extractSurroundingContext(anchor)
            )
        }

        logger.debug("Extracted {} link descriptors (original HTML: ~{} chars)", links.size, rawHtml.length)

        return LinkExtractionResult(
            pageTitle = pageTitle,
            pageDescription = pageDescription,
            links = links,
            validRelativePaths = validRelativePaths,
            allAbsoluteUrls = allAbsoluteUrls,
            pathToAbsoluteUrl = pathToAbsoluteUrl
        )
    }

    /**
     * Resolves a human-readable display text for an anchor using a fallback chain:
     * inner text -> aria-label -> child img alt -> title attribute -> "[no text]".
     */
    private fun resolveDisplayText(anchor: Element): String {
        val text = anchor.text().trim()
        if (text.isNotBlank()) return if (text.length > 80) text.take(80) + "..." else text

        val ariaLabel = anchor.attr("aria-label").trim()
        if (ariaLabel.isNotBlank()) return "[aria-label: $ariaLabel]"

        val imgAlt = anchor.selectFirst("img[alt]")?.attr("alt")?.trim()
        if (!imgAlt.isNullOrBlank()) return "[img: $imgAlt]"

        val title = anchor.attr("title").trim()
        if (title.isNotBlank()) return "[title: $title]"

        return "[no text]"
    }

    /**
     * Walks ancestor elements to determine the page section a link lives in,
     * using semantic HTML elements and ARIA roles.
     */
    private fun detectSection(anchor: Element): String {
        var current: Element? = anchor.parent()
        while (current != null) {
            val tag = current.tagName().lowercase()
            val role = current.attr("role").lowercase()
            when {
                tag == "nav" || role == "navigation" -> return "nav"
                tag == "header" || role == "banner" -> return "header"
                tag == "main" || role == "main" -> return "main"
                tag == "aside" || role == "complementary" -> return "sidebar"
                tag == "footer" || role == "contentinfo" -> return "footer"
            }
            current = current.parent()
        }
        return "other"
    }

    /**
     * Extracts surrounding text context for a link. Walks up the DOM (max 3 levels)
     * to find the nearest ancestor with meaningful non-link text, then returns
     * ~100 chars before and ~50 chars after the anchor.
     */
    private fun extractSurroundingContext(anchor: Element): String {
        var target: Element = anchor

        repeat(3) {
            val parent = target.parent() ?: return ""
            val context = collectContextFromParent(anchor, parent)
            if (context.isNotBlank()) return context
            target = parent
        }
        return ""
    }

    private fun collectContextFromParent(anchor: Element, container: Element): String {
        val pathToAnchor = mutableSetOf<Element>()
        var el: Element? = anchor
        while (el != null && el !== container) {
            pathToAnchor.add(el)
            el = el.parent()
        }

        val before = StringBuilder()
        val after = StringBuilder()
        var reachedAnchor = false

        fun walk(nodes: List<Node>) {
            for (node in nodes) {
                when {
                    node === anchor -> reachedAnchor = true
                    node is TextNode -> {
                        val text = node.text().trim()
                        if (text.isNotBlank()) {
                            if (!reachedAnchor) {
                                if (before.isNotEmpty()) before.append(" ")
                                before.append(text)
                            } else {
                                if (after.isNotEmpty()) after.append(" ")
                                after.append(text)
                            }
                        }
                    }
                    node is Element && node.tagName() != "a" -> {
                        if (node in pathToAnchor) {
                            walk(node.childNodes())
                        } else {
                            val text = node.text().trim()
                            if (text.isNotBlank()) {
                                val truncated = if (text.length > 100) text.take(100) else text
                                if (!reachedAnchor) {
                                    if (before.isNotEmpty()) before.append(" ")
                                    before.append(truncated)
                                } else {
                                    if (after.isNotEmpty()) after.append(" ")
                                    after.append(truncated)
                                }
                            }
                        }
                    }
                }
            }
        }

        walk(container.childNodes())

        val beforeText = before.toString().let {
            if (it.length > 100) it.takeLast(100) else it
        }
        val afterText = after.toString().let {
            if (it.length > 50) it.take(50) else it
        }

        return (beforeText.trimStart() + " " + afterText.trimEnd()).trim()
    }
}
