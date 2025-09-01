package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.IVisualAnalysisAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.models.valueobjects.SearchResult
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class VisualAnalysisAgentAdkImpl : IVisualAnalysisAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Visual analysis answer for the user's query based on current page view")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Concise answer derived from what is visible")
                    .build(),
            )
        )
        .required(listOf("content", "sources"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("visualAnalysisAgent")
        description("Answer queries using only what a human can infer from the current page view")
        model(ModelIds.GEMINI_2_5_LITE.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.2F)
                .build()
        )
        instruction(
            (
                """
                You are the Visual Analysis agent. Respond to the user's query using the visible information on the page's screenshot only. If unsure, say no relevant information found.
                If the screenshot does not contain answer to the query, just say so.
                Return ONLY a JSON object matching the schema with fields {"result"}.

                Expected output shape:
                {
                  "answer": "answer to the user query",
                }
                """
            ).trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class VisualResponse(
        val answer: String
    )

    override suspend fun generate(input: IVisualAnalysisAgent.VisualAnalysisInput): IVisualAnalysisAgent.VisualAnalysisOutput {
        logger.debug("Visual analysis for {}", input.searchQuery)

        val userPrompt = buildString {
            appendLine(input.searchQuery.query)
        }

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
            Content.fromParts(Part.fromBytes(input.screenshotBytes, "image/jpeg"), Part.fromText(userPrompt)),
            RunConfig.builder().apply {
                setStreamingMode(RunConfig.StreamingMode.NONE)
                setMaxLlmCalls(100)
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

        val response = Json.decodeFromString<VisualResponse>(llmResponse)

        val result = SearchResult(
            originalQuery = input.searchQuery,
            content = response.answer,
            sources = listOf(input.searchQuery.url)
        )

        return IVisualAnalysisAgent.VisualAnalysisOutput(result)
    }
}


