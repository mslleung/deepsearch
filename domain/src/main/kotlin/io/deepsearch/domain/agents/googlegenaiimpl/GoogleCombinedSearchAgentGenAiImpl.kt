package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GoogleSearch
import com.google.genai.types.Part
import com.google.genai.types.Tool
import com.google.genai.types.UrlContext
import io.deepsearch.domain.agents.IGoogleCombinedSearchAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.withRateLimitRetry
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An agent that enables BOTH Google Search and URL Context tools, so the model can
 * discover relevant pages and then fetch and reason over their content.
 *
 * Reference: URL context docs indicating combination with Google Search.
 * https://ai.google.dev/gemini-api/docs/url-context
 */
class GoogleCombinedSearchAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IGoogleCombinedSearchAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val searchTool = Tool.builder()
        .googleSearch(GoogleSearch.builder().build())
        .build()

    private val urlContextTool = Tool.builder()
        .urlContext(UrlContext.builder().build())
        .build()

    private val systemInstruction = """
        You are a Google search agent. 
        First, use Google Search tool to find the most relevant pages
        for the user's query and target site. Then use the URL Context tool to fetch those page(s)
        and answer using ONLY the retrieved content. Provide citations when possible.
    """.trimIndent()

    override suspend fun generate(
        input: io.deepsearch.domain.agents.GoogleCombinedSearchInput
    ): io.deepsearch.domain.agents.GoogleCombinedSearchOutput {
        val query = input.searchQuery.query
        val url = input.searchQuery.url
        logger.debug("Combined search: '{}' on {}", query, url)

        val userPrompt = buildString {
            appendLine("$query $url")
        }

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withRateLimitRetry(this::class.simpleName!!) {
            client.models.generateContent(
                modelId,
                userPrompt,
                GenerateContentConfig.builder()
                    .temperature(0.2F)
                    .tools(listOf(searchTool, urlContextTool))
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

        val contentText = response.text() ?: ""

        logger.debug("Combined search results: '{}'", contentText)

        return io.deepsearch.domain.agents.GoogleCombinedSearchOutput(
            answer = contentText,
            answerSources = listOf(url),
            tokenUsage = tokenUsage
        )
    }
}


