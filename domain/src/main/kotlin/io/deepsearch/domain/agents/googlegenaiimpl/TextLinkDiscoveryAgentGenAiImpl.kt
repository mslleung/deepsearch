package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.ITextLinkDiscoveryAgent
import io.deepsearch.domain.agents.TextLinkDiscoveryInput
import io.deepsearch.domain.agents.TextLinkDiscoveryOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Agent that discovers relevant URLs from text content using LLM analysis.
 *
 * Analyzes text (e.g., file search results, markdown) to find URLs that are
 * relevant to the user's query. Filters to same-domain URLs.
 */
class TextLinkDiscoveryAgentGenAiImpl(
    private val client: Client,
    private val dispatcherProvider: IDispatcherProvider
) : ITextLinkDiscoveryAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val relevantLinkSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A relevant link with URL and reasoning")
        .properties(
            mapOf(
                "url" to Schema.builder().type("STRING")
                    .description("The absolute URL found in the text")
                    .build(),
                "reason" to Schema.builder().type("STRING")
                    .description("Brief explanation of why this link is relevant to the query")
                    .build()
            )
        )
        .required(listOf("url", "reason"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Collection of relevant links found in the text")
        .properties(
            mapOf(
                "links" to Schema.builder().type("ARRAY").items(relevantLinkSchema)
                    .description("List of relevant URLs found in the text content")
                    .build()
            )
        )
        .required(listOf("links"))
        .build()

    @Serializable
    private data class RelevantLinkJson(
        val url: String,
        val reason: String
    )

    @Serializable
    private data class LinkAnalysisResponse(
        val links: List<RelevantLinkJson>
    )

    private val systemInstruction = """
        You are a link discovery agent that analyzes text content to find relevant URLs.
        
        Given text content and a user query, identify URLs that would help answer the query.
        
        Instructions:
        1. Scan the text for any URLs (http:// or https://)
        2. Evaluate each URL for relevance to the user's query
        3. Only return URLs that are directly relevant and would help answer the query
        4. Provide a brief reason explaining why each URL is relevant
        5. Return the URLs exactly as found in the text
        
        If no relevant URLs are found, return an empty links array.
    """.trimIndent()

    override suspend fun generate(input: TextLinkDiscoveryInput): TextLinkDiscoveryOutput {
        logger.debug("Analyzing text for relevant links, query: '{}'", input.query)

        val baseHost = try {
            // URL-encode spaces in the source URL path to handle malformed URLs
            val encodedUrl = input.sourceUrl.replace(" ", "%20")
            URI(encodedUrl).host?.lowercase()
        } catch (e: Exception) {
            logger.warn("Failed to parse source URL '{}': {}", input.sourceUrl, e.message)
            return TextLinkDiscoveryOutput.empty()
        } ?: return TextLinkDiscoveryOutput.empty()

        if (!URL_PATTERN.containsMatchIn(input.text)) {
            logger.debug("No URLs found in text, skipping LLM analysis")
            return TextLinkDiscoveryOutput.empty()
        }

        val (textWithOnlySameDomainUrls, externalUrlCount) = stripExternalUrls(input.text, baseHost)

        if (!URL_PATTERN.containsMatchIn(textWithOnlySameDomainUrls)) {
            logger.debug("No same-domain URLs found in text (removed {} external URLs), skipping LLM analysis", externalUrlCount)
            return TextLinkDiscoveryOutput.empty()
        }

        if (externalUrlCount > 0) {
            logger.debug("Stripped {} external domain URLs from text before LLM analysis", externalUrlCount)
        }

        val userPrompt = buildPrompt(input.query, textWithOnlySameDomainUrls)
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val links = try {
            val response = withContext(dispatcherProvider.io) {
                retryLlmCall<LinkAnalysisResponse>(this@TextLinkDiscoveryAgentGenAiImpl::class.simpleName!!) {
                    val result = client.models.generateContent(
                        modelId,
                        userPrompt,
                        GenerateContentConfig.builder()
                            .temperature(1.0F)
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

            response.links
                .distinctBy { it.url }
                .mapNotNull { validateAndMapLink(it, input.text, baseHost) }
        } catch (e: Exception) {
            logger.warn("Failed to analyze text for links: {}", e.message)
            emptyList()
        }

        logger.debug("Text link discovery found {} relevant links", links.size)

        return TextLinkDiscoveryOutput(
            links = links,
            tokenUsage = tokenUsage
        )
    }

    private fun isSameDomain(url: String, baseHost: String): Boolean {
        return try {
            // URL-encode spaces to handle malformed URLs
            val encodedUrl = url.replace(" ", "%20")
            val linkUri = URI(encodedUrl)
            val linkHost = linkUri.host?.lowercase()
            linkHost == baseHost && (linkUri.scheme == "http" || linkUri.scheme == "https")
        } catch (e: Exception) {
            false
        }
    }

    private fun stripExternalUrls(text: String, baseHost: String): Pair<String, Int> {
        var externalUrlCount = 0
        val filteredText = URL_PATTERN.replace(text) { matchResult ->
            val url = matchResult.value
            if (isSameDomain(url, baseHost)) {
                url
            } else {
                externalUrlCount++
                ""
            }
        }
        return Pair(filteredText, externalUrlCount)
    }

    private fun buildPrompt(query: String, text: String): String {
        return buildString {
            appendLine("User Query: $query")
            appendLine()
            appendLine("Text content to analyze:")
            appendLine(text)
        }
    }

    private fun validateAndMapLink(
        linkJson: RelevantLinkJson,
        originalText: String,
        baseHost: String
    ): WebpageLink? {
        val url = linkJson.url.trimEnd('.', ',', ')', ']', ';', ':')

        if (!originalText.contains(url)) {
            logger.warn("Filtering hallucinated link not found in original text: '{}'", url)
            return null
        }

        if (!isSameDomain(url, baseHost)) {
            logger.warn("Filtering external domain or invalid link from LLM response: '{}'", url)
            return null
        }

        return WebpageLink(
            url = url,
            source = LinkSource.FILE_CONTENT,
            reason = linkJson.reason
        )
    }

    companion object {
        private val URL_PATTERN = """https?://[^\s<>"{}|\\^`\[\]]+""".toRegex()
    }
}
