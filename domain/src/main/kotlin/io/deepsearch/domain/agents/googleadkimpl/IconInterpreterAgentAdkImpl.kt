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
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
 

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
                    .nullable(true)
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
            - If the image is meaningless or empty or cannot be interpreted, output nothing.

            Expected output shape:
            {
                "label": string | null
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class IconInterpretationResponse(
        val label: String?
    )

    override suspend fun generate(input: IconInterpreterInput): IconInterpreterOutput {
        logger.debug("Interpreting icon ({} bytes, {})", input.bytes.size, input.mimeType.value)

        // Plain colour icons carry no semantic meaning (they are just uniform background blocks).
        // Skip LLM invocation to save cost/latency and return a null label instead.
        if (isPlainColourIcon(input.bytes, input.mimeType)) {
            logger.debug("Detected plain-colour icon; returning null label")
            return IconInterpreterOutput(label = null)
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

        if (!response.label.isNullOrBlank()) {
            logger.debug("Icon interpreted: {}", response.label)

            val formattedLabel = formatIconLabel(response.label)

            return IconInterpreterOutput(label = formattedLabel)
        } else {
            logger.debug("Icon cannot be interpreted ({} bytes)", input.bytes.size)
            return IconInterpreterOutput(label = null)
        }
    }

    /**
     * Detects if the provided icon image is effectively a solid, uniform colour.
     * Plain colour icons carry no semantic meaning for UI intent, so they are skipped.
     */
    private fun isPlainColourIcon(bytes: ByteArray, mimeType: ImageMimeType): Boolean {
        // only deal with jpegs for now
        if (mimeType != ImageMimeType.JPEG) return false

        return try {
            val bufferedImage = ImageIO.read(ByteArrayInputStream(bytes)) ?: return false
            val width = bufferedImage.width
            val height = bufferedImage.height
            if (width <= 0 || height <= 0) return false

            val firstPixel = bufferedImage.getRGB(0, 0)
            // Sample a grid of pixels instead of all for performance.
            val sampleSteps = 8
            val stepX = maxOf(1, width / sampleSteps)
            val stepY = maxOf(1, height / sampleSteps)

            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    if (bufferedImage.getRGB(x, y) != firstPixel) {
                        return false
                    }
                    x += stepX
                }
                y += stepY
            }
            true
        } catch (e: Exception) {
            logger.warn("Failed to inspect JPEG for uniform colour, proceeding with LLM", e)
            false
        }
    }

    /**
     * Formats the final icon label string.
     * - Wraps in square brackets
     * - Appends " icon" only if the label does not already end with "icon" (case-insensitive)
     * - Returns null if the input is null or blank
     */
    private fun formatIconLabel(label: String): String {
        val endsWithIcon = label.endsWith("icon", ignoreCase = true)
        val text = if (endsWithIcon) label else "$label icon"
        return "[$text]"
    }
}