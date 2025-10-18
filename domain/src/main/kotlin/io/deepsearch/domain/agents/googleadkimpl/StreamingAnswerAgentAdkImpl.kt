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
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Streaming Answer agent that incrementally builds an answer as new markdown batches arrive.
 * Determines when the answer is complete enough to address the user's query.
 */
class StreamingAnswerAgentAdkImpl : IStreamingAnswerAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

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
                    .build()
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
            
            Your task:
            1. Review the current answer (if any) and the new batch of markdown content
            2. Extract relevant information from the new markdowns that helps answer the user's query
            3. Update the answer by incorporating new relevant information
            4. Determine if the answer is now complete enough to fully address the user's query
            
            Instructions for answer updates:
            - If there's no current answer, create an initial answer from the markdown batch
            - If there's a current answer, enhance it with any new relevant information from the batch
            - Only include information that directly addresses the user's query
            - Do not invent information not present in the content
            - Maintain a clear, well-structured response
            - If the new batch contains no relevant information, return the current answer unchanged
            
            Instructions for completeness determination (IMPORTANT - be conservative):
            - Set isComplete=true ONLY if you are confident the answer comprehensively addresses all aspects of the user's query
            - Consider: Does the answer provide sufficient detail? Are there obvious gaps?
            - Err on the side of caution: if unsure, set isComplete=false to allow more information gathering
            - It's better to process more content than to stop too early with an incomplete answer
            - If the query asks for multiple pieces of information, ensure all are covered before marking complete
            
            Expected output shape:
            {
                "answer": "your comprehensive answer text",
                "isComplete": true/false
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class StreamingAnswerResponse(
        val answer: String,
        val isComplete: Boolean
    )

    override suspend fun generate(input: StreamingAnswerInput): StreamingAnswerOutput {
        logger.debug(
            "Generating streaming answer for query: '{}', batch size: {} markdowns, has current answer: {}",
            input.query,
            input.markdownBatch.size,
            input.currentAnswer != null
        )

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

        val markdownContent = input.markdownBatch.joinToString("\n\n---\n\n")
        
        val userPrompt = buildString {
            appendLine("Search Query: ${input.query}")
            appendLine()
            if (input.currentAnswer != null) {
                appendLine("Current Answer:")
                appendLine(input.currentAnswer)
                appendLine()
            }
            appendLine("New Markdown Batch (${input.markdownBatch.size} pages):")
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
            "Streaming answer updated: {} chars, complete: {}",
            response.answer.length,
            response.isComplete
        )
        
        return StreamingAnswerOutput(
            updatedAnswer = response.answer,
            isComplete = response.isComplete
        )
    }
}

