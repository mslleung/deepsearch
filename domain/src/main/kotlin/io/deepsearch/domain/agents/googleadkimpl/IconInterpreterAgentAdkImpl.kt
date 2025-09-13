package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.IconInterpreterInput
import io.deepsearch.domain.agents.IconInterpreterOutput
import io.deepsearch.domain.agents.infra.ModelIds
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Multimodal icon interpretation agent.
 *
 * Given a small icon image, produce a short, human-friendly label, a confidence score, and optional synonyms.
 */
class IconInterpreterAgentAdkImpl : IIconInterpreterAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Interpretation of a UI icon")
        .properties(
            mapOf(
                "label" to Schema.builder()
                    .type("STRING")
                    .description("label describing the icon, e.g., 'search', 'download', 'settings'")
                    .build()
            )
        )
        .required(listOf("label"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("iconInterpreterAgent")
        description("Interpret a UI icon image and output a concise label")
        model(ModelIds.GEMINI_2_5_LITE.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.0F)
                .build()
        )
        instruction(
            """
            You are given a small UI icon image. Produce a label to accurately describe the image.
            
            Instructions:
            - Interpret the image. 
            - If the image is a simple UI icon, output a concise, lowercase label
              ex. "search", "download", "settings", "hamburger menu", "close", "play", "pause", "tick", "cross".
            - If the image is not a simple UI icon, output a more detailed label describing the icon.

            Expected output shape:
            {
                "label": string
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class IconInterpretationResponse(
        val label: String
    )

    override suspend fun generate(input: IconInterpreterInput): IconInterpreterOutput {
        logger.debug("Interpreting icon ({} bytes, {})", input.bytes.size, input.mimeType.value)

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
                Part.fromBytes(input.bytes, input.mimeType.value),
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

        val response = Json.decodeFromString<IconInterpretationResponse>(llmResponse)

        return IconInterpreterOutput(
            label = response.label
        )
    }
}