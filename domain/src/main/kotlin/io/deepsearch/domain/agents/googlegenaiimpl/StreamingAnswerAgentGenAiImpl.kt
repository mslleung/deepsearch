package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.StreamingAnswerInput
import io.deepsearch.domain.agents.StreamingAnswerOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Streaming Answer agent that incrementally builds an answer as new markdown batches arrive.
 * Focuses solely on building and improving the answer, not on determining completeness.
 */
class StreamingAnswerAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IStreamingAnswerAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Updated answer")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Updated comprehensive answer to the search query, incorporating new information from the markdown batch")
                    .build()
            )
        )
        .required(listOf("answer"))
        .build()

    private val systemInstruction = """
        You are a answer generation agent that builds comprehensive answers for a query based on provided text content
        
        Answer quality:
        - The answer should be as comprehensive as possible
        - The answer should be standalone and serve as a direct answer to the user query
        - If the user query is a statement instead of a question, focus on supplying relevant information
        - If there is a lack of information, the answer should just say so
        - Only include information that supports the answer in addressing the query
        - Do not invent information not present in the content
        - Use markdown styling as applicable, such as headings/list etc.
        - The answer should be in the same language as the input query.
        - The answer should be placed in a json structured output
        
        Conflict resolution:
        - Some markdowns may contain conflicting/overlapping information
        - You should prioritize markdown data from official pages and deprioritize data from blog posts or publications
        - No need to note the discrepancy, just include the information you deem to be most credible

        Expected output shape:
        {
            "answer": "your comprehensive answer text"
        }
    """.trimIndent()

    @Serializable
    private data class StreamingAnswerResponse(
        val answer: String
    )

    override suspend fun generate(input: StreamingAnswerInput): StreamingAnswerOutput {
        logger.debug(
            "Generating streaming answer for query: '{}', batch size: {} markdowns, has current answer: {}",
            input.query,
            input.markdownBatch.size,
            input.currentAnswer != null
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        val emptyTokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.markdownBatch.isEmpty()) {
            logger.warn("Empty markdown batch received, returning current answer or empty")
            return StreamingAnswerOutput(
                updatedAnswer = input.currentAnswer ?: "",
                tokenUsage = emptyTokenUsage
            )
        }

        return processBatch(input.query, input.currentAnswer, input.markdownBatch)
    }

    /**
     * Process a single batch of markdowns and produce a partial answer.
     */
    private suspend fun processBatch(
        query: String,
        currentAnswer: String?,
        markdowns: List<String>
    ): StreamingAnswerOutput {
        logger.debug("Processing batch of {} markdowns", markdowns.size)

        val markdownContent = markdowns.joinToString("\n\n---\n\n")
        
        val userPrompt = buildString {
            appendLine("Search Query: $query")
            appendLine()
            appendLine("Text content:")
            if (currentAnswer != null) {
                appendLine(currentAnswer)
                appendLine("\n\n---\n\n")
            }
            appendLine(markdownContent)
        }

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = retryLlmCall<StreamingAnswerResponse> {
            val result = client.models.generateContent(
                modelId,
                userPrompt,
                GenerateContentConfig.builder()
                    .temperature(0F)
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

        logger.debug(
            "Batch processed: answer {} chars",
            response.answer.length
        )
        
        return StreamingAnswerOutput(
            updatedAnswer = response.answer,
            tokenUsage = tokenUsage
        )
    }
}


