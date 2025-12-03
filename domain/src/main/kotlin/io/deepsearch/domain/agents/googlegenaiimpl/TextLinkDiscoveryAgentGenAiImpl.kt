package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ITextLinkDiscoveryAgent
import io.deepsearch.domain.agents.TextLinkDiscoveryInput
import io.deepsearch.domain.agents.TextLinkDiscoveryOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebpageLink
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
    private val client: Client
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
        5. Only include URLs that belong to the same domain as the source URL
        6. Return the URLs exactly as found in the text
        
        If no relevant URLs are found, return an empty links array.
    """.trimIndent()

    override suspend fun generate(input: TextLinkDiscoveryInput): TextLinkDiscoveryOutput {
        logger.debug("Analyzing text for relevant links, query: '{}'", input.query)

        // Extract base host for domain filtering
        val baseHost = try {
            URI(input.sourceUrl).host?.lowercase()
        } catch (e: Exception) {
            logger.warn("Could not parse source URL: {}", input.sourceUrl)
            null
        }

        if (baseHost == null) {
            return TextLinkDiscoveryOutput(
                links = emptyList(),
                tokenUsage = TokenUsageMetrics.empty()
            )
        }

        // Quick check: if text has no URLs, skip LLM call
        val urlPattern = """https?://[^\s<>"{}|\\^`\[\]]+""".toRegex()
        if (!urlPattern.containsMatchIn(input.text)) {
            logger.debug("No URLs found in text, skipping LLM analysis")
            return TextLinkDiscoveryOutput(
                links = emptyList(),
                tokenUsage = TokenUsageMetrics.empty()
            )
        }

        val userPrompt = buildString {
            appendLine("User Query: ${input.query}")
            appendLine("Source URL (for domain filtering): ${input.sourceUrl}")
            appendLine()
            appendLine("Text content to analyze:")
            appendLine(input.text.take(15000)) // Limit text size
        }

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val links = try {
            val response = retryLlmCall<LinkAnalysisResponse> {
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

            response.links.distinctBy { it.url }.filter { linkJson ->
                try {
                    val linkUri = URI(linkJson.url)
                    val linkHost = linkUri.host?.lowercase()
                    linkHost == baseHost && (linkUri.scheme == "http" || linkUri.scheme == "https")
                } catch (e: Exception) {
                    logger.warn("Invalid URL in LLM response: '{}'", linkJson.url)
                    false
                }
            }.map { linkJson ->
                WebpageLink(
                    url = linkJson.url.trimEnd('.', ',', ')', ']', ';', ':'),
                    source = LinkSource.FILE_CONTENT,
                    reason = linkJson.reason
                )
            }
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
}

