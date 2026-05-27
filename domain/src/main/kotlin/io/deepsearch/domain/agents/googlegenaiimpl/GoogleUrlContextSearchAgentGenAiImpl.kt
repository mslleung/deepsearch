package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import com.google.genai.types.Tool
import com.google.genai.types.UrlContext
import io.deepsearch.domain.agents.IGoogleUrlContextSearchAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An agent that uses Google GenAI SDK with the URL Context tool to answer questions using the content
 * of specific URLs provided by the user.
 *
 * Reference: URL context tool – Google Gemini API
 * https://ai.google.dev/gemini-api/docs/url-context
 */
class GoogleUrlContextSearchAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IGoogleUrlContextSearchAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val urlContextTool = Tool.builder()
        .urlContext(UrlContext.builder().build())
        .build()

    private val systemInstruction = """
        You are a URL-context search agent. Use the URL Context tool to retrieve the provided URL(s)
        and answer the user's query using ONLY the content found at those URL(s).
        Provide a concise, factual answer. If information is not present, say you cannot find it on the page.
    """.trimIndent()

    override suspend fun generate(
        input: io.deepsearch.domain.agents.GoogleUrlContextSearchInput
    ): io.deepsearch.domain.agents.GoogleUrlContextSearchOutput {
        val query = input.query
        val urls = input.urls

        logger.debug("URL-context search: '{}' on {} url(s)", query, urls.size)

        require(urls.count() <= 20) { "URL context can take at most 20 urls." }

        // Including the URLs directly in the prompt enables the URL Context tool to fetch them
        val userPrompt = buildString {
            appendLine("$query ${urls.joinToString(" ")}")
        }

        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withContext(dispatcherProvider.io) {
            client.models.generateContent(
                modelId,
                userPrompt,
                GenerateContentConfig.builder()
                    .temperature(1.0F)
                    .thinkingConfig(
                        ThinkingConfig.builder()
                            .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                            .build()
                    )
                    .tools(listOf(urlContextTool))
                    .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                    .build()
            )
        }
        
        // Extract token usage
        response.usageMetadata().ifPresent { metadata ->
            tokenUsage = TokenUsageMetrics(
                modelName = modelId,
                promptTokens = metadata.promptTokenCount().orElse(0),
                outputTokens = metadata.candidatesTokenCount().orElse(0),
                totalTokens = metadata.totalTokenCount().orElse(0)
            )
        }

        val llmResponse = response.text() ?: ""

        logger.debug("URL-context search results: '{}' from {} source(s)", llmResponse, urls.size)

        return io.deepsearch.domain.agents.GoogleUrlContextSearchOutput(
            content = llmResponse,
            sources = urls,
            tokenUsage = tokenUsage
        )
    }
}
