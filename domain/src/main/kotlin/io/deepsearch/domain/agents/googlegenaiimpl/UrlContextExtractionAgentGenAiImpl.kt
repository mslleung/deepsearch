package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import com.google.genai.types.Tool
import com.google.genai.types.UrlContext
import io.deepsearch.domain.agents.IUrlContextExtractionAgent
import io.deepsearch.domain.agents.UrlContextExtractionInput
import io.deepsearch.domain.agents.UrlContextExtractionOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.withRateLimitRetry
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebsiteContext
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * GenAI implementation of UrlContextExtractionAgent.
 * Uses Gemini URL Context tool to fetch page content and extract website context.
 * 
 * Note: Cannot use JSON response mode with URL Context tool (API limitation).
 * The response is parsed as structured text instead.
 * 
 * Reference: https://ai.google.dev/gemini-api/docs/url-context
 */
class UrlContextExtractionAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IUrlContextExtractionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val urlContextTool = Tool.builder()
        .urlContext(UrlContext.builder().build())
        .build()

    private val systemInstruction = """
        You are a website context extractor. Your task is to:
        1. Use the URL context tool to fetch the content of the provided URL
        2. Extract a comprehensive summary of the webpage
        
        Provide a 2-4 sentence summary that includes:
        - What this webpage/website is about
        - Its main purpose
        - Key features, products, or services offered
        - Any other context useful for understanding what queries this page could answer
        
        Be thorough but concise. Focus on information that would help understand the scope of the website.
    """.trimIndent()

    override suspend fun generate(input: UrlContextExtractionInput): UrlContextExtractionOutput {
        logger.debug("Extracting context from URL: {}", input.url)

        val modelId = ModelIds.GEMINI_3_5_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val responseText = withContext(dispatcherProvider.io) {
            withRateLimitRetry("${this@UrlContextExtractionAgentGenAiImpl::class.simpleName}") {
                val result = client.models.generateContent(
                    modelId,
                    "Please fetch and extract context from this URL: ${input.url}",
                    GenerateContentConfig.builder()
                        .tools(listOf(urlContextTool))
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

                result.text() ?: throw RuntimeException("No text response from URL context model")
            }
        }

        val websiteContext = WebsiteContext(
            url = input.url,
            contentSummary = responseText.trim()
        )

        logger.debug("Extracted context summary: '{}'", websiteContext.contentSummary.take(200))

        return UrlContextExtractionOutput(
            websiteContext = websiteContext,
            tokenUsage = tokenUsage
        )
    }
}
