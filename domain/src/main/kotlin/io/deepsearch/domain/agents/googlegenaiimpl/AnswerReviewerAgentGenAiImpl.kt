package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.AnswerReviewerInput
import io.deepsearch.domain.agents.AnswerReviewerOutput
import io.deepsearch.domain.agents.IAnswerReviewerAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Answer Reviewer agent that evaluates whether an answer adequately addresses a search query.
 * Returns a boolean indicating if the answer is satisfactory.
 */
class AnswerReviewerAgentGenAiImpl(
    private val client: Client
) : IAnswerReviewerAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Completeness assessment of the answer")
        .properties(
            mapOf(
                "isComplete" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether the answer is comprehensive enough to fully address the user's query")
                    .build(),
                "reason" to Schema.builder()
                    .type("STRING")
                    .description("Reason for the isComplete decision")
                    .build()
            )
        )
        .required(listOf("isComplete", "reason"))
        .build()

    private val systemInstruction = """
        You are an answer reviewer that determines whether an answer is complete enough to address the user's query.
        
        Completeness determination:
        - The answer is considered complete only if it addresses all concerns and aspects of the query
        - The answer must explicitly address the user's query
        - If the answer suggests a lack of information or any ambiguities, return false to allow more information gathering
        - It's better to process more content than to stop too early with an incomplete answer
        - If the query asks for multiple pieces of information, ensure all are covered before marking complete

        Exceptional cases:
        - If the query is entirely invalid or gibberish, complete right away
          Examples of invalid queries:
          Good morning! (invalid)
          Hello (invalid)
          *f&dbst4$ (gibberish)

        Expected output shape:
        {
            "isComplete": true/false
            "reason": "reason for the isComplete decision"
        }
    """.trimIndent()

    @Serializable
    private data class AnswerReviewerResponse(
        val isComplete: Boolean,
        val reason: String
    )

    override suspend fun generate(input: AnswerReviewerInput): AnswerReviewerOutput {
        logger.debug("Reviewing answer completeness for query: '{}'", input.query)

        // If there's no answer, it's definitely not complete
        if (input.currentAnswer.isBlank()) {
            logger.debug("No answer provided, marking as incomplete")
            return AnswerReviewerOutput(
                isComplete = false,
                reason = "No answer has been generated yet"
            )
        }

        val userPrompt = buildString {
            appendLine("Search Query: ${input.query}")
            appendLine()
            appendLine("Current Answer:")
            appendLine(input.currentAnswer)
        }

        val response = retryLlmCall<AnswerReviewerResponse> {
            val result = client.models.generateContent(
                ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
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

            result.text() ?: throw RuntimeException("No text response from model")
        }

        logger.debug(
            "Answer review complete: isComplete: {}, reason: {}",
            response.isComplete,
            response.reason
        )

        return AnswerReviewerOutput(
            isComplete = response.isComplete,
            reason = response.reason
        )
    }
}


