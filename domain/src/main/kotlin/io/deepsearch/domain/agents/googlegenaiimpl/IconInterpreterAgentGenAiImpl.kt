package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.IconInterpreterInput
import io.deepsearch.domain.agents.IconInterpreterOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Multimodal icon interpretation agent.
 *
 * Given a small icon image, produce a short, human-friendly label, a confidence score, and optional synonyms.
 */
class IconInterpreterAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IIconInterpreterAgent {

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

    private val systemInstruction = """
        You are given a small UI icon image. Produce a label to accurately describe the image.
        
        Instructions:
        - Interpret the image
        - If the image is a simple UI icon, output a concise, lowercase label
          ex. "search", "download", "settings", "hamburger menu", "close", "play", "pause", "tick", "cross".
        - If the image is not a simple UI icon, output a more detailed label describing the icon.
        - If the image is ambiguous, output all possibilities
          ex. "cross/close", "arrow down/accordion expanded", "arrow up/accordion collapsed"
        - If the image is meaningless or empty or cannot be interpreted, output nothing.

        Expected output shape:
        {
            "label": string | null
        }
    """.trimIndent()

    @Serializable
    private data class IconInterpretationResponse(
        val label: String?
    )

    override suspend fun generate(input: IconInterpreterInput): IconInterpreterOutput {
        logger.debug("Interpreting icon ({} bytes, {})", input.bytes.size, input.mimeType.value)

        // Plain colour icons carry no semantic meaning (they are just uniform background blocks).
        // Catch these early to reduce token usage.
        val modelId = ModelIds.GEMINI_3_5_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)
        
        if (isPlainColorIcon(input.bytes, input.mimeType)) {
            logger.debug("Icon is a plain color image, skipping LLM interpretation")
            return IconInterpreterOutput(
                label = null,
                tokenUsage = tokenUsage
            )
        }
        
        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<IconInterpretationResponse>(this@IconInterpreterAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(Part.fromBytes(input.bytes, input.mimeType.value))),
                    GenerateContentConfig.builder()
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                        .build()
                )

                result.checkFinishReason()
                
                // Extract token usage
                result.usageMetadata().ifPresent { metadata ->
                    tokenUsage = TokenUsageMetrics(
                        modelName = modelId,
                        promptTokens = metadata.promptTokenCount().orElse(0),
                        outputTokens = metadata.candidatesTokenCount().orElse(0),
                        totalTokens = metadata.totalTokenCount().orElse(0)
                    )
                }

                result.text() ?: throw RuntimeException("No text response from model")
            }
        }

        logger.debug("Icon interpretation complete: label='{}'", response.label)

        return IconInterpreterOutput(
            label = response.label,
            tokenUsage = tokenUsage
        )
    }

    /**
     * Detects if the provided icon is a plain color image (uniform single color).
     * Such icons carry no semantic meaning and should not be sent to the LLM.
     */
    private fun isPlainColorIcon(bytes: ByteArray, @Suppress("UNUSED_PARAMETER") mimeType: ImageMimeType): Boolean {
        return try {
            val bufferedImage: BufferedImage = ImageIO.read(ByteArrayInputStream(bytes)) ?: return false
            val width = bufferedImage.width
            val height = bufferedImage.height

            if (width == 0 || height == 0) return true

            val firstPixelRgb = bufferedImage.getRGB(0, 0)
            val firstColor = Color(firstPixelRgb, true)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val rgb = bufferedImage.getRGB(x, y)
                    val color = Color(rgb, true)
                    if (color != firstColor) {
                        return false
                    }
                }
            }

            true
        } catch (e: Exception) {
            logger.warn("Failed to check if icon is plain color, proceeding with LLM", e)
            false
        }
    }
}


