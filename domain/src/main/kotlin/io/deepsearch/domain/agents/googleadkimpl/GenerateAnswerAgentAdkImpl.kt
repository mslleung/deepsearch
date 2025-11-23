package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.GenerateAnswerInput
import io.deepsearch.domain.agents.GenerateAnswerOutput
import io.deepsearch.domain.agents.IGenerateAnswerAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Generate Answer agent that synthesizes markdown content from multiple crawled pages
 * to produce a comprehensive answer to the user's query.
 */
class GenerateAnswerAgentAdkImpl : IGenerateAnswerAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Generated answer to the user's search query based on crawled content")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Comprehensive answer to the search query synthesized from all markdown content")
                    .build()
            )
        )
        .required(listOf("answer"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("generateAnswerAgent")
        description("Generate comprehensive answers from crawled markdown content")
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
            You are a answer extractor agent. Given multiple markdowns and a user's search query, generate a comprehensive, accurate answer to the query.
            
            Instructions:
            - Directly address the user's specific search query
            - Provide a clear, well-structured, and comprehensive answer
            - Reference specific details, facts, and examples from the content
            - Do not invent information not present in the content
            - Organize the answer logically with proper formatting when appropriate
            - If the query cannot be answered based on the markdowns at all, return "No information found"
            
            Expected output shape:
            {
                "answer": string
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class GenerateAnswerResponse(
        val answer: String
    )

    override suspend fun generate(input: GenerateAnswerInput): GenerateAnswerOutput {
        logger.debug(
            "Generating answer for query: '{}' from {} chars of markdown",
            input.query,
            input.markdowns.length
        )

        val userPrompt = """
            Search Query: ${input.query}
            
            Markdown Content from Crawled Pages:
            ${input.markdowns}
        """.trimIndent()

        val response = retryLlmCall<GenerateAnswerResponse> {
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

        logger.debug("Generated answer: {} chars", response.answer.length)
        return GenerateAnswerOutput(answer = response.answer,
            tokenUsage = TokenUsageMetrics.empty(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId))
    }
}

