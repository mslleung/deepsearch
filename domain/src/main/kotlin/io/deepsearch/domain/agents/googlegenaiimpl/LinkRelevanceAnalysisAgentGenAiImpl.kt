package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An agent that analyzes HTML to identify links relevant to a query.
 * The agent extracts <a href> links from the HTML and ranks them by relevance.
 */
class LinkRelevanceAnalysisAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : ILinkRelevanceAnalysisAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

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
        
        Given an input of extracted text from a webpage with <a> links (using relative paths).
         
        Your task is to:
        1. Identify all <a href> links in the provided text
        2. Using the surrounding context, analyze which links are relevant to the user's query
        3. Be very permissive, look for any link that may be relevant directly or indirectly to the user query.
        4. Include links that may lead to pages that have a high chance to contain linkage to pages related to the query. (multi-hop)
        5. Return all relevant links with reasons why they are relevant and a relevance score
        6. The links must be unique and exactly the same as given in the source (use the relative path exactly as shown)
        
        Scoring Guidelines (1-10):
        - 10: Highly likely to directly answer the query (e.g., pricing page for a pricing query)
        - 7-9: Very relevant, likely contains substantial information
        - 4-6: Moderately relevant, may contain some useful information
        - 1-3: Tangentially related, might lead to relevant content via multiple hops
        
        Focus on links that would help answer the user's query or provide more detailed information.
        
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

        val (cleanedHtml, validRelativePaths) = cleanHtml(input.html, input.url)
        logger.debug("Extracted {} unique links from cleaned HTML", validRelativePaths.size)

        if (validRelativePaths.isEmpty()) {
            logger.debug("No link found in cleaned HTML")

            return LinkRelevanceAnalysisOutput(
                links = emptyList(),
                tokenUsage = TokenUsageMetrics.empty()
            )
        }

        val userPrompt = buildString {
            appendLine("Query: ${input.query}")
            appendLine()
            appendLine("Extracted html text:")
            appendLine(cleanedHtml)
        }

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)
        
        val links = try {
            val response = withContext(dispatcherProvider.io) {
                retryLlmCall<LinkAnalysisResponse>(this@LinkRelevanceAnalysisAgentGenAiImpl::class.simpleName!!) {
                    val result = client.models.generateContent(
                        modelId,
                        userPrompt,
                        GenerateContentConfig.builder()
                            .temperature(0.2F)
                            .responseSchema(outputSchema)
                            .responseMimeType("application/json")
                            .thinkingConfig(
                                ThinkingConfig.builder()
                                    .thinkingBudget(0)
                                    .build()
                            )
                            .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                            .build()
                    )

                    result.checkFinishReason()
                    
                    // Extract token usage
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

            // Validate and resolve LLM-returned relative paths to absolute URLs
            val baseUri = input.url.toSafeUri()
            val validatedLinks = response.links.distinctBy { it.url }.mapNotNull { linkJson ->
                if (linkJson.url in validRelativePaths) {
                    // Reconstruct absolute URL from relative path + base
                    val absoluteUrl = baseUri.resolve(linkJson.url).toString()
                    // Clamp score to valid range 1-10
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
            tokenUsage = tokenUsage
        )
    }

    /**
     * Cleans HTML by removing non-content elements that don't contribute to link analysis.
     * Preserves anchor tags and their surrounding context for better link relevance analysis.
     * Filters anchor tags to only include those from the same host as the url parameter.
     * Converts hrefs to relative paths to save tokens.
     * @return A Pair of (cleaned HTML text, set of valid relative paths)
     */
    private fun cleanHtml(rawHtml: String, url: String): Pair<String, Set<String>> {
        val doc: Document = Jsoup.parse(rawHtml)
        val baseUri = url.toSafeUri()
        val baseHost = baseUri.host

        // Step 1: Remove obvious non-content elements (scripts, styles, etc.)
        doc.select(
            "script, style, noscript, template, svg, canvas, meta, link[rel], iframe, object, embed, " +
                    "head, title, base"
        ).remove()

        // Step 2: Filter anchor tags and convert to relative paths
        val validSchemes = setOf("http", "https")
        val validRelativePaths = mutableSetOf<String>()
        
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            
            // Remove anchors with blank href, fragment-only links (e.g., "#section"), 
            // or no meaningful text content
            if (href.isBlank() || href.startsWith("#")) {
                anchor.remove()
                return@forEach
            }

            try {
                val resolvedUri = baseUri.resolve(href)
                val scheme = resolvedUri.scheme?.lowercase()
                val resolvedHost = resolvedUri.host

                // Keep only HTTP/HTTPS links with same host
                if (scheme !in validSchemes) {
                    logger.debug("Removing anchor with non-HTTP scheme '{}': {}", scheme, href)
                    anchor.remove()
                } else if (resolvedHost != null && !resolvedHost.equals(baseHost, ignoreCase = true)) {
                    anchor.remove()
                } else {
                    // Convert to relative path (path + query, stripping fragment)
                    val relativePath = buildString {
                        append(resolvedUri.rawPath ?: "/")
                        resolvedUri.rawQuery?.let { append("?").append(it) }
                    }
                    
                    // Update href to use relative path
                    anchor.attr("href", relativePath)
                    validRelativePaths.add(relativePath)
                }
            } catch (e: Exception) {
                // Invalid href, remove it
                logger.debug("Removing anchor with invalid href '{}': {}", href, e.message)
                anchor.remove()
            }
        }
        logger.debug("Filtered anchor tags to host: {}", baseHost)

        // Step 3: Remove duplicate links (keep only first occurrence of each unique relative path)
        val seenPaths = mutableSetOf<String>()
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (href.isNotBlank()) {
                if (href in seenPaths) {
                    anchor.remove()
                } else {
                    seenPaths.add(href)
                }
            }
        }
        logger.debug("Removed duplicate links, kept {} unique paths", seenPaths.size)

        // Step 4: Remove form elements (not part of navigational content)
        doc.select("form, input, select, textarea").remove()

        // Step 5: Remove comments and processing instructions
        doc.select("*").forEach { element ->
            val nodesToRemove = element.childNodes().filter { node ->
                node.nodeName() == "#comment" || node.nodeName() == "#pi"
            }
            nodesToRemove.forEach { node -> node.remove() }
        }

        // Step 6: Strip attributes except those useful for link relevance analysis
        // For anchors: keep href (critical) and aria-label (provides context)
        // For other elements: keep only semantic role attributes that indicate navigation structure
        val semanticRoles = setOf(
            "navigation", "menu", "menuitem", "main",
            "complementary", "contentinfo", "banner"
        )

        doc.select("*").forEach { element ->
            val attrsToKeep = when {
                element.tagName() == "a" -> {
                    // For anchors: href is critical, aria-label can provide additional context
                    element.attributes().filter { attr ->
                        attr.key in setOf("href", "aria-label")
                    }
                }

                else -> {
                    // For other elements: keep only semantic role attributes
                    element.attributes().filter { attr ->
                        attr.key == "role" && attr.value in semanticRoles
                    }
                }
            }
            element.clearAttributes()
            attrsToKeep.forEach { attr ->
                element.attr(attr.key, attr.value)
            }
        }

        // Step 7: Truncate text content to reduce token usage while preserving context
        doc.select("*").forEach { element ->
            element.textNodes().forEach { textNode ->
                val text = textNode.text().trim()
                if (text.isNotEmpty() && text.length > 100) {
                    textNode.text(text.take(100) + "...")
                }
            }
        }

        // Step 8: Remove empty elements iteratively (but keep anchors even if empty)
        var changed = true
        while (changed) {
            changed = false
            val emptyElements = doc.select("*").filter { element ->
                element.tagName() != "a" && // Keep all anchor tags
                        element.children().isEmpty() &&
                        element.ownText().isBlank() &&
                        element.tagName() !in setOf("br", "hr")
            }
            if (emptyElements.isNotEmpty()) {
                emptyElements.forEach { it.remove() }
                changed = true
            }
        }

        // Step 9: Extract text content while preserving <a> tags with relative paths
        val cleanedHtml = extractTextWithLinks(doc.body())
        logger.debug("Cleaned HTML character count: {} (original: ~{})", cleanedHtml.length, rawHtml.length)
        return Pair(cleanedHtml, validRelativePaths)
    }

    /**
     * Recursively extracts text content from an element while preserving <a> tags.
     * All other HTML tags are stripped, leaving only text and anchor links.
     * Each <a> tag is placed on its own line for better readability.
     * 
     * @param element The element to extract text and links from
     * @param isRoot Whether this is the root call (for final trimming)
     * @return A string containing text with embedded <a> tags, each link on its own line
     */
    private fun extractTextWithLinks(element: Element?, isRoot: Boolean = true): String {
        if (element == null) return ""
        
        val result = StringBuilder()
        
        element.childNodes().forEach { node ->
            when (node) {
                // Text nodes: append directly with space management
                is TextNode -> {
                    val text = node.text().trim()
                    if (text.isNotBlank()) {
                        // Add space before text if needed (but not newline)
                        if (result.isNotEmpty() && !result.endsWith(" ") && !result.endsWith("\n")) {
                            result.append(" ")
                        }
                        result.append(text)
                    }
                }
                // Anchor elements: preserve the tag with href on its own line
                is Element if node.tagName() == "a" -> {
                    val href = node.attr("href")
                    val ariaLabel = node.attr("aria-label")
                    val linkText = node.text()

                    // Ensure we're on a new line before the anchor
                    if (result.isNotEmpty() && !result.endsWith("\n")) {
                        result.append("\n")
                    }
                    
                    result.append("<a href=\"").append(href).append("\"")
                    if (ariaLabel.isNotBlank()) {
                        result.append(" aria-label=\"").append(ariaLabel).append("\"")
                    }
                    result.append(">").append(linkText).append("</a>")
                    
                    // Add newline after the anchor
                    result.append("\n")
                }
                // All other elements: recurse but don't include the tag itself
                is Element -> {
                    val childContent = extractTextWithLinks(node, isRoot = false)
                    if (childContent.isNotBlank()) {
                        result.append(childContent)
                    }
                }
            }
        }
        
        // Only trim at the root level to preserve internal newlines
        return if (isRoot) {
            result.toString().trim()
        } else {
            result.toString()
        }
    }

}


