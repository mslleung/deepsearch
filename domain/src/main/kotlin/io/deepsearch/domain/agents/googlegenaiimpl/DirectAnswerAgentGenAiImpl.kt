package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.DirectAnswerInput
import io.deepsearch.domain.agents.DirectAnswerOutput
import io.deepsearch.domain.agents.IDirectAnswerAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Direct answer agent for benchmarking purposes.
 *
 * Given a webpage screenshot, HTML content, and a search query, 
 * produce a direct answer based on the visual and textual content.
 * This agent is designed for testing and benchmarking scenarios.
 */
class DirectAnswerAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IDirectAnswerAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Direct answer to a search query based on webpage content")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Direct answer to the search query based on the webpage content")
                    .build()
            )
        )
        .required(listOf("answer"))
        .build()

    private val systemInstruction = """
        You are given a webpage screenshot, HTML content, and a search query. 
        Your task is to provide a direct, accurate answer to the search query based on the visual and textual content of the webpage.
        
        Instructions:
        - Analyze both the screenshot and HTML content to understand the webpage
        - Focus on answering the specific search query provided
        - Provide a clear, direct, and accurate answer
        - Use specific details from the webpage to support your answer
        - If the information is not available on the page, state that clearly
        - Keep the answer concise but comprehensive

        Expected output shape:
        {
            "answer": string
        }
    """.trimIndent()

    @Serializable
    private data class DirectAnswerResponse(
        val answer: String
    )

    override suspend fun generate(input: DirectAnswerInput): DirectAnswerOutput {
        logger.debug("Generating direct answer for query: '{}' on URL: {}", 
            input.searchQuery.query, input.searchQuery.url)

        val userPrompt = """
            Search Query: ${input.searchQuery.query}
            URL: ${input.searchQuery.url}
            
            HTML Content:
            ${input.html}
        """.trimIndent()

        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)
        
        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<DirectAnswerResponse>(this@DirectAnswerAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    listOf(
                        Content.fromParts(
                            Part.fromText(userPrompt),
                            Part.fromBytes(input.screenshotBytes, "image/png")
                        )
                    ),
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

        logger.debug("Direct answer generated: {} chars", response.answer.length)
        return DirectAnswerOutput(
            answer = response.answer,
            tokenUsage = tokenUsage
        )
    }
}


