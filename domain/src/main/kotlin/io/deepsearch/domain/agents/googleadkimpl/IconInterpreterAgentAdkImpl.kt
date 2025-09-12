package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.IconInterpretationInput
import io.deepsearch.domain.agents.IconInterpretationOutput
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
        .description("Interpretation of a small UI icon")
        .properties(
            mapOf(
                "label" to Schema.builder()
                    .type("STRING")
                    .description("Short label describing the icon, e.g., 'search', 'download', 'settings'")
                    .build(),
                "confidence" to Schema.builder()
                    .type("NUMBER")
                    .description("Confidence score between 0.0 and 1.0")
                    .build(),
                "hints" to Schema.builder()
                    .type("ARRAY")
                    .description("Optional synonyms or related labels")
                    .items(Schema.builder().type("STRING").build())
                    .build()
            )
        )
        .required(listOf("label", "confidence"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("iconInterpreterAgent")
        description("Interpret a small UI icon image and output a concise label with confidence")
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
            You are given a small UI icon image. Produce:
            - "label": a concise, lowercase label (single or few words) like "search", "download", "settings", "hamburger menu", "close", "play", "pause".
            - "confidence": 0.0..1.0 indicating how confident you are in the label.
            - "hints": optional synonyms or related words (short list).
            
            Important:
            - Return ONLY a JSON object matching the schema exactly.
            - Prefer common UI icon names over verbose descriptions.
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class IconInterpretationResponse(
        val label: String,
        val confidence: Double,
        val hints: List<String> = emptyList()
    )

    override suspend fun generate(input: IconInterpretationInput): IconInterpretationOutput {
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
                Part.fromBytes(input.bytes, input.mimeType.value)
            ),
            RunConfig.builder().apply {
                setStreamingMode(RunConfig.StreamingMode.NONE)
                setMaxLlmCalls(50)
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

        val response = try {
            Json.decodeFromString<IconInterpretationResponse>(llmResponse)
        } catch (ex: Exception) {
            logger.warn("Failed to parse icon interpretation JSON, falling back. Raw: {}", llmResponse)
            IconInterpretationResponse(label = "unknown", confidence = 0.0, hints = emptyList())
        }

        return IconInterpretationOutput(
            label = response.label,
            confidence = response.confidence.coerceIn(0.0, 1.0),
            hints = response.hints
        )
    }
}