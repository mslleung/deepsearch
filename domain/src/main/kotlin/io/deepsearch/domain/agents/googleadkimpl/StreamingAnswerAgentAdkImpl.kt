package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Streaming Answer agent that incrementally builds an answer as new markdown batches arrive.
 * Focuses solely on building and improving the answer, not on determining completeness.
 */
class StreamingAnswerAgentAdkImpl : IStreamingAnswerAgent {

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

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("streamingAnswerAgent")
        description("Incrementally build answers from markdown batches")
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
            You are a streaming answer agent that builds comprehensive answers incrementally as new markdown content arrives.
            
            Instructions for answer updates:
            - If there's no current answer, create an initial answer from the markdowns
            - If there's a current answer, enhance it with any new relevant information
            - If the new content contains no relevant information, return the current answer unchanged
            
            Answer quality:
            - The answer should be as comprehensive as possible
            - The answer should be standalone and serve as a direct answer to the user query
            - If the user query is a statement instead of a question, focus on supplying relevant information
            - There is no temporal significance of the markdowns, the answer should not reveal our streaming approach
              For example, prefer to say "from the information" instead of "from the new batch of information"
            - If there is a lack of information, the answer should just say so
            - Only include information that directly addresses the user's query
            - Do not invent information not present in the content
            - Use markdown styling as applicable, such as headings/list etc.
            - The answer should be in the same language as the input query.

            Expected output shape:
            {
                "answer": "your comprehensive answer text"
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

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

        if (input.markdownBatch.isEmpty()) {
            logger.warn("Empty markdown batch received, returning current answer or empty")
            return StreamingAnswerOutput(
                updatedAnswer = input.currentAnswer ?: ""
            )
        }

        return processBatch(input.query, input.currentAnswer, input.markdownBatch)
    }

    /**
     * Process a single batch of markdowns (up to BATCH_SIZE) and produce a partial answer.
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
            if (currentAnswer != null) {
                appendLine("Current Answer:")
                appendLine(currentAnswer)
                appendLine()
            }
            appendLine("New Markdown:")
            appendLine(markdownContent)
        }

        val response = retryLlmCall<StreamingAnswerResponse> {
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
            "Batch processed: answer {} chars",
            response.answer.length
        )
        
        return StreamingAnswerOutput(
            updatedAnswer = response.answer
        )
    }
}

