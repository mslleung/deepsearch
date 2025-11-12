package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
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
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Answer Reviewer agent that determines whether an answer is complete enough to address the user's query.
 * This agent focuses solely on completeness determination, not on building or improving the answer.
 */
class AnswerReviewerAgentAdkImpl : IAnswerReviewerAgent {

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

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("answerReviewerAgent")
        description("Determine if an answer is complete enough to address the user's query")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0F)
                .thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(0)
                        .build()
                )
                .build()
        )
        instruction(
            """
            You are an answer reviewer that determines whether an answer is complete enough to address the user's query.
            
            Completeness determination:
            - The answer is considered complete only if it addresses all concerns and aspects of the query
            - If the answer suggests a lack of information to answer the query, return false to allow more information gathering
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
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class AnswerReviewerResponse(
        val isComplete: Boolean,
        val reason: String
    )

    override suspend fun generate(input: AnswerReviewerInput): AnswerReviewerOutput {
        logger.debug(
            "Reviewing answer completeness for query: '{}'",
            input.query
        )

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
            val session = runner
                .sessionService()
                .createSession(
                    this::class.simpleName,
                    this::class.simpleName,
                    null,
                    null
                )
                .await()

            var llmResponse = ""

            val eventsFlow = runner.runAsync(
                session,
                Content.fromParts(Part.fromText(userPrompt)),
                RunConfig.builder().apply {
                    setStreamingMode(RunConfig.StreamingMode.NONE)
                    setMaxLlmCalls(1)
                }.build()
            ).asFlow()

            eventsFlow.collect { event ->
                if (event.finalResponse() && event.content().isPresent) {
                    val content = event.content().get()
                    if (content.parts().isPresent
                        && !content.parts().get().isEmpty()
                        && content.parts().get()[0].text().isPresent
                    ) {
                        if (!event.partial().orElse(false)) {
                            llmResponse = content.parts().get()[0].text().get()
                        }
                    }
                }
            }

            llmResponse
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

