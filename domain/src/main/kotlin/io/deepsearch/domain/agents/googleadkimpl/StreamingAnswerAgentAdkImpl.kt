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
import io.deepsearch.domain.agents.infra.decodeFromStringWithCodeBlocks
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
 * Determines when the answer is complete enough to address the user's query.
 * 
 * For large batches (>20 markdowns), processes them in parallel sub-batches and combines results.
 */
class StreamingAnswerAgentAdkImpl : IStreamingAnswerAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    companion object {
        private const val BATCH_SIZE = 20
    }

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Updated answer with completeness indicator")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Updated comprehensive answer to the search query, incorporating new information from the markdown batch")
                    .build(),
                "isComplete" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether the answer is comprehensive enough to fully address the user's query")
                    .build(),
                "reason" to Schema.builder()
                    .type("STRING")
                    .description("Reason for the isComplete decision")
                    .build(),
            )
        )
        .required(listOf("answer", "isComplete"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("streamingAnswerAgent")
        description("Incrementally build answers from markdown batches and determine completeness")
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
            - If the new batch contains no relevant information, return the current answer unchanged
            
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
            
            Instructions for completeness determination (IMPORTANT - be conservative):
            - Set isComplete=true ONLY if you are confident the answer comprehensively addresses all aspects of the user's query
            - Consider: Does the answer provide sufficient detail? Are there obvious gaps?
            - Err on the side of caution: if unsure, set isComplete=false to allow more information gathering
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
                "answer": "your comprehensive answer text"
                "isComplete": true/false
                "reason": "reason for the isComplete decision"
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    // Schema and agent for combining multiple partial answers
    private val combineOutputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Combined answer from multiple partial answers with completeness indicator")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Comprehensive answer combining all partial answers")
                    .build(),
                "isComplete" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether the combined answer is comprehensive enough to fully address the user's query")
                    .build()
            )
        )
        .required(listOf("answer", "isComplete"))
        .build()

    private val combineAgent: LlmAgent = LlmAgent.builder().run {
        name("combineAnswersAgent")
        description("Combine multiple partial answers into a coherent final answer")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        outputSchema(combineOutputSchema)
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
            You are an answer combining agent that synthesizes multiple partial answers into one coherent comprehensive answer in response to a query.
            
            Your task:
            1. Review the current answer (if any) and multiple partial answers from different sources
            2. Combine all relevant information into a single, well-structured comprehensive answer
            3. Remove redundant information while preserving all unique relevant details
            4. Determine if the combined answer is complete enough to fully address the user's query
            
            Instructions for combining:
            - Do not invent information not present in the partial answers
            - If the current answer exists, integrate it with the new partial answers
            
            Instructions for completeness determination (IMPORTANT - be conservative):
            - Set isComplete=true ONLY if you are confident the combined answer comprehensively addresses all aspects of the user's query
            - Consider: Does the answer provide sufficient detail? Are there obvious gaps?
            - Err on the side of caution: if unsure, set isComplete=false to allow more information gathering
            - It's better to process more content than to stop too early with an incomplete answer
            - If the query asks for multiple pieces of information, ensure all are covered before marking complete
            
            Expected output shape:
            {
                "answer": "your comprehensive combined answer text",
                "isComplete": true/false
            }
            """.trimIndent()
        )
        build()
    }

    private val combineRunner = InMemoryRunner(combineAgent)

    @Serializable
    private data class StreamingAnswerResponse(
        val answer: String,
        val isComplete: Boolean,
        val reason: String
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
                updatedAnswer = input.currentAnswer ?: "",
                isComplete = false
            )
        }

        // Use parallel batching if we have more than BATCH_SIZE markdowns
        return if (input.markdownBatch.size <= BATCH_SIZE) {
            // Single batch - process directly with current behavior
            processBatch(input.query, input.currentAnswer, input.markdownBatch)
        } else {
            // Multiple batches - process in parallel and combine
            processInParallelBatches(input.query, input.currentAnswer, input.markdownBatch)
        }
    }

    /**
     * Process multiple batches of markdowns in parallel, then combine the partial answers.
     */
    private suspend fun processInParallelBatches(
        query: String,
        currentAnswer: String?,
        markdowns: List<String>
    ): StreamingAnswerOutput = coroutineScope {
        val batches = markdowns.chunked(BATCH_SIZE)
        logger.debug("Processing {} markdowns in {} parallel batches", markdowns.size, batches.size)

        // Process all batches in parallel, each producing a partial answer
        // Note: We don't pass currentAnswer to individual batches, as they work independently
        val partialOutputs = batches.mapIndexed { index, batch ->
            async {
                logger.debug("Processing batch {} of {} ({} markdowns)", index + 1, batches.size, batch.size)
                processBatch(query, null, batch)
            }
        }.awaitAll()

        // Extract just the answer strings from the partial outputs
        val partialAnswers = partialOutputs.map { it.updatedAnswer }

        logger.debug("Combining {} partial answers into final answer", partialAnswers.size)

        // Combine all partial answers into a final answer
        combinePartialAnswers(query, currentAnswer, partialAnswers)
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

        val markdownContent = markdowns.joinToString("\n\n---\n\n")
        
        val userPrompt = buildString {
            appendLine("Search Query: $query")
            appendLine()
            if (currentAnswer != null) {
                appendLine("Current Answer:")
                appendLine(currentAnswer)
                appendLine()
            }
            appendLine("New Markdown Batch (${markdowns.size} pages):")
            appendLine(markdownContent)
        }

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

        val response = Json.decodeFromStringWithCodeBlocks<StreamingAnswerResponse>(llmResponse)

        logger.debug(
            "Batch processed: answer {} chars, complete: {}, reason: {}",
            response.answer.length,
            response.isComplete,
            response.reason
        )
        
        return StreamingAnswerOutput(
            updatedAnswer = response.answer,
            isComplete = response.isComplete
        )
    }

    /**
     * Combine multiple partial answers into a single coherent answer using the combine agent.
     */
    private suspend fun combinePartialAnswers(
        query: String,
        currentAnswer: String?,
        partialAnswers: List<String>
    ): StreamingAnswerOutput {
        logger.debug("Combining {} partial answers", partialAnswers.size)

        val session = combineRunner
            .sessionService()
            .createSession(
                "combineAnswersAgent",
                "combineAnswersAgent",
                null,
                null
            )
            .await()

        var llmResponse = ""

        val userPrompt = buildString {
            appendLine("Search Query: $query")
            appendLine()
            if (currentAnswer != null) {
                appendLine("Current Answer:")
                appendLine(currentAnswer)
                appendLine()
            }
            appendLine("Partial Answers to Combine (${partialAnswers.size} answers):")
            partialAnswers.forEachIndexed { index, partialAnswer ->
                appendLine()
                appendLine("=== Partial Answer ${index + 1} ===")
                appendLine(partialAnswer)
            }
        }

        val eventsFlow = combineRunner.runAsync(
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

        val response = Json.decodeFromStringWithCodeBlocks<StreamingAnswerResponse>(llmResponse)

        logger.debug(
            "Combined answer: {} chars, complete: {}",
            response.answer.length,
            response.isComplete
        )
        
        return StreamingAnswerOutput(
            updatedAnswer = response.answer,
            isComplete = response.isComplete
        )
    }
}

