package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.IQueryAnsweringAgent
import io.deepsearch.domain.agents.QueryAnsweringInput
import io.deepsearch.domain.agents.QueryAnsweringOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.decodeFromStringWithCodeBlocks
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Multimodal query answering agent.
 *
 * Given a webpage screenshot, HTML content, and a search query, 
 * produce a comprehensive answer based on the visual and textual content.
 */
class QueryAnsweringAgentAdkImpl : IQueryAnsweringAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Answer to a search query based on webpage content")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Comprehensive answer to the search query based on the webpage content")
                    .build()
            )
        )
        .required(listOf("answer"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("queryAnsweringAgent")
        description("Answer search queries based on webpage screenshot and HTML content")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0F)
                .build()
        )
        instruction(
            """
            You are given a webpage screenshot, HTML content, and a search query. 
            Your task is to provide a comprehensive answer to the search query based on the visual and textual content of the webpage.
            
            Instructions:
            - Analyze both the screenshot and HTML content to understand the webpage
            - Focus on answering the specific search query provided
            - Provide a clear, accurate, and comprehensive answer
            - Use specific details from the webpage to support your answer
            - If the information is not available on the page, state that clearly

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
    private data class QueryAnsweringResponse(
        val answer: String
    )

    override suspend fun generate(input: QueryAnsweringInput): QueryAnsweringOutput {
        logger.debug("Answering query: '{}' for URL: {}", input.searchQuery.query, input.searchQuery.url)

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

        val response = Json.decodeFromStringWithCodeBlocks<QueryAnsweringResponse>(llmResponse)

        logger.debug("Query answered successfully")
        return QueryAnsweringOutput(answer = response.answer)
    }
}
