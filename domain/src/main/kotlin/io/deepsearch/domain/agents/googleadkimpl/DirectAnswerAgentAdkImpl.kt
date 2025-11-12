package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.DirectAnswerInput
import io.deepsearch.domain.agents.DirectAnswerOutput
import io.deepsearch.domain.agents.IDirectAnswerAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Direct answer agent for benchmarking purposes.
 *
 * Given a webpage screenshot, HTML content, and a search query, 
 * produce a direct answer based on the visual and textual content.
 * This agent is designed for testing and benchmarking scenarios.
 */
class DirectAnswerAgentAdkImpl : IDirectAnswerAgent {

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

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("directAnswerAgent")
        description("Provide direct answers to search queries based on webpage screenshot and HTML content")
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
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class DirectAnswerResponse(
        val answer: String
    )

    override suspend fun generate(input: DirectAnswerInput): DirectAnswerOutput {
        logger.debug("Generating direct answer for query: '{}' on URL: {}", 
            input.searchQuery.query, input.searchQuery.url)

        val response = retryLlmCall<DirectAnswerResponse> {
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
                Content.fromParts(
                    Part.fromBytes(input.screenshotBytes, "image/png"),
                    Part.fromText(
                        """
                        Search Query: ${input.searchQuery.query}
                        URL: ${input.searchQuery.url}
                        
                        HTML Content:
                        ${input.html}
                        """.trimIndent()
                    )
                ),
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

        logger.debug("Direct answer generated successfully")
        return DirectAnswerOutput(answer = response.answer)
    }
}
