package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ILinkRelevanceAnalysisAgent
import io.deepsearch.domain.agents.LinkRelevanceAnalysisInput
import io.deepsearch.domain.agents.LinkRelevanceAnalysisOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.decodeFromStringWithCodeBlocks
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An agent that analyzes HTML to identify links relevant to a query.
 * The agent extracts <a href> links from the HTML and ranks them by relevance.
 */
class LinkRelevanceAnalysisAgentAdkImpl : ILinkRelevanceAnalysisAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Serializable
    private data class RelevantLinkJson(
        val url: String,
        val reason: String
    )

    @Serializable
    private data class LinkAnalysisResponse(
        val links: List<RelevantLinkJson>
    )

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("linkRelevanceAnalysisAgent")
        description("Agent to identify relevant links in HTML")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.2F)
                .responseMimeType("application/json")
                .thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(0)
                        .build()
                )
                .build()
        )
        instruction(
            """
                You are a link relevance analysis agent. Your task is to:
                1. Extract all <a href> links from the provided HTML
                2. Analyze which links are most relevant to the user's query
                3. Return all relevant links with reasons why they are relevant
                
                Focus on links that would help answer the user's query or provide more detailed information.
                
                Return your response in JSON format:
                {
                  "links": [
                    {
                      "url": "https://example.com/page",
                      "reason": "This page contains information about..."
                    }
                  ]
                }
                
                If there are no relevant links, return an empty links array.
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    override suspend fun generate(input: LinkRelevanceAnalysisInput): LinkRelevanceAnalysisOutput {
        logger.debug("Analyzing link relevance for query: '{}'", input.query)

        val cleanedHtml = cleanHtml(input.html, input.url)

        val userPrompt = buildString {
            appendLine("Query: ${input.query}")
            appendLine()
            appendLine("Cleaned html:")
            appendLine(cleanedHtml)
        }

        val session = runner
            .sessionService()
            .createSession(
                this::class.simpleName,
                this::class.simpleName,
                null,
                null
            )
            .await()

        val eventsFlow = runner.runAsync(
            session,
            Content.fromParts(Part.fromText(userPrompt)),
            RunConfig.builder().apply {
                setStreamingMode(RunConfig.StreamingMode.NONE)
                setMaxLlmCalls(1)
            }.build()
        ).asFlow()

        var responseText = ""
        eventsFlow.collect { event ->
            if (event.finalResponse()) {
                val contentOpt = event.content()
                if (contentOpt.isPresent) {
                    val content = contentOpt.get()
                    val partsOpt = content.parts()
                    if (partsOpt.isPresent && partsOpt.get().isNotEmpty()) {
                        val textOpt = partsOpt.get()[0].text()
                        if (textOpt.isPresent) {
                            responseText = textOpt.get()
                        }
                    }
                }
            }
        }

        val links = try {
            val response = Json.decodeFromStringWithCodeBlocks<LinkAnalysisResponse>(responseText)
            response.links.map { linkJson ->
                WebpageLink(
                    url = linkJson.url,
                    source = LinkSource.LINK_RELEVANCE,
                    reason = linkJson.reason
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse link analysis response: {}", e.message)
            logger.debug("Response text: {}", responseText)
            emptyList()
        }

        logger.debug("Link relevance analysis found {} relevant links", links.size)

        return LinkRelevanceAnalysisOutput(links)
    }

    /**
     * Extracts the base domain from a URL (scheme + host).
     * For example: "https://example.com/path" -> "https://example.com"
     */
    private fun extractBaseDomain(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            "$scheme://$host"
        } catch (e: Exception) {
            logger.warn("Failed to parse URL for domain extraction: {}", url, e)
            null
        }
    }

    /**
     * Cleans HTML by removing non-content elements that don't contribute to link analysis.
     * Preserves anchor tags and their surrounding context for better link relevance analysis.
     * Filters anchor tags to only include those from the same domain as the url parameter.
     */
    private fun cleanHtml(rawHtml: String, url: String): String {
        val doc: Document = Jsoup.parse(rawHtml)
        val baseDomain = extractBaseDomain(url)

        // Step 1: Remove obvious non-content elements (scripts, styles, etc.)
        doc.select(
            "script, style, noscript, template, svg, canvas, meta, link[rel], iframe, object, embed, " +
            "head, title, base"
        ).remove()

        // Step 2: Filter anchor tags to only include those from the same domain
        if (baseDomain != null) {
            doc.select("a[href]").forEach { anchor ->
                val href = anchor.attr("href")
                if (href.isNotBlank() && !href.startsWith(baseDomain)) {
                    anchor.remove()
                }
            }
            logger.debug("Filtered anchor tags to base domain: {}", baseDomain)
        }

        // Step 3: Remove form elements (not part of navigational content)
        doc.select("form, input, button, select, textarea").remove()

        // Step 4: Remove comments and processing instructions
        doc.select("*").forEach { element ->
            val nodesToRemove = element.childNodes().filter { node ->
                node.nodeName() == "#comment" || node.nodeName() == "#pi"
            }
            nodesToRemove.forEach { node -> node.remove() }
        }

        // Step 5: Strip attributes except those useful for link relevance analysis
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

        // Step 6: Truncate text content to reduce token usage while preserving context
        doc.select("*").forEach { element ->
            element.textNodes().forEach { textNode ->
                val text = textNode.text().trim()
                if (text.isNotEmpty() && text.length > 100) {
                    textNode.text(text.take(100) + "...")
                }
            }
        }

        // Step 7: Remove empty elements iteratively (but keep anchors even if empty)
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

        val cleanedHtml = doc.outerHtml()
        logger.debug("Cleaned HTML character count: {} (original: ~{})", cleanedHtml.length, rawHtml.length)
        return cleanedHtml
    }
}

