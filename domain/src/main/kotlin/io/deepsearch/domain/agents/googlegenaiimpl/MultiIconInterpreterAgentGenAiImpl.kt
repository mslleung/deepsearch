package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IMultiIconInterpreterAgent
import io.deepsearch.domain.agents.MultiIconInterpreterInput
import io.deepsearch.domain.agents.MultiIconInterpreterOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Multimodal multi-icon interpretation agent.
 *
 * Given multiple small icon images, produce short, human-friendly labels for each.
 * This agent is designed to process multiple icons efficiently by:
 * - Batching icons into groups of up to 50 per LLM call
 * - Processing batches in parallel to maximize throughput
 * - Pre-filtering plain color icons to reduce LLM usage
 */
class MultiIconInterpreterAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IMultiIconInterpreterAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    companion object {
        private const val BATCH_SIZE = 10
    }

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Interpretations of multiple UI icons")
        .properties(
            mapOf(
                "icons" to Schema.builder()
                    .type("ARRAY")
                    .description("Array of icon interpretations in order")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .description("Single icon interpretation")
                            .properties(
                                mapOf(
                                    "label" to Schema.builder()
                                        .type("STRING")
                                        .description("Label describing the icon, e.g., 'search', 'download', 'settings'")
                                        .nullable(true)
                                        .build()
                                )
                            )
                            .required(listOf("label"))
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("icons"))
        .build()

    private val systemInstruction = """
        You are given multiple small UI icon images in sequence. 
        For each icon, produce a label to accurately describe the image.
        
        Instructions:
        - Interpret each image in order.
        - If the image is a simple UI icon, output a concise, lowercase label
          ex. "search", "download", "settings", "hamburger menu", "close", "play", "pause", "tick", "cross".
        - If the image is not a simple UI icon, output a more detailed label describing the icon.
        - If the image is meaningless or empty or cannot be interpreted, output null for the label.
        - Return labels in the same order as the images were provided.

        Expected output shape:
        {
            "icons": [
                {"label": string | null},
                {"label": string | null},
                ...
            ]
        }
    """.trimIndent()

    @Serializable
    private data class MultiIconInterpretationResponse(
        val icons: List<IconLabelResponse>
    )

    @Serializable
    private data class IconLabelResponse(
        val label: String?
    )

    override suspend fun generate(input: MultiIconInterpreterInput): MultiIconInterpreterOutput {
        logger.debug("Interpreting {} icons (will process in batches of {})", input.icons.size, BATCH_SIZE)

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        val emptyTokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.icons.isEmpty()) {
            return MultiIconInterpreterOutput(
                interpretations = emptyList(),
                tokenUsage = emptyTokenUsage
            )
        }

        // Split icons into batches of BATCH_SIZE and process in parallel
        return if (input.icons.size <= BATCH_SIZE) {
            // Single batch - process directly
            val (output, tokenUsage) = processBatch(input.icons)
            output.copy(tokenUsage = tokenUsage)
        } else {
            // Multiple batches - process in parallel
            processInParallelBatches(input.icons)
        }
    }

    /**
     * Process multiple batches of icons in parallel.
     */
    private suspend fun processInParallelBatches(icons: List<MultiIconInterpreterInput.IconItem>): MultiIconInterpreterOutput =
        coroutineScope {
            val batches = icons.chunked(BATCH_SIZE)
            logger.debug("Processing {} icons in {} parallel batches", icons.size, batches.size)

            // Process all batches in parallel
            val batchResults = batches.map { batch ->
                async {
                    processBatch(batch)
                }
            }.awaitAll()

            // Combine results from all batches in order
            val allInterpretations = batchResults.flatMap { it.first.interpretations }
            val aggregatedTokenUsage = batchResults.fold(TokenUsageMetrics.empty(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)) { acc, (_, tokenUsage) ->
                TokenUsageMetrics(
                    modelName = acc.modelName,
                    promptTokens = acc.promptTokens + tokenUsage.promptTokens,
                    outputTokens = acc.outputTokens + tokenUsage.outputTokens,
                    totalTokens = acc.totalTokens + tokenUsage.totalTokens
                )
            }
            
            MultiIconInterpreterOutput(
                interpretations = allInterpretations,
                tokenUsage = aggregatedTokenUsage
            )
        }

    /**
     * Process a single batch of icons (up to BATCH_SIZE).
     * Returns a Pair of (output, tokenUsage) for aggregation.
     */
    private suspend fun processBatch(icons: List<MultiIconInterpreterInput.IconItem>): Pair<MultiIconInterpreterOutput, TokenUsageMetrics> {
        logger.debug("Processing batch of {} icons", icons.size)

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        // Pre-filter plain color icons to reduce LLM usage
        val plainColorPositions = mutableSetOf<Int>()
        val iconsToProcess = icons.filterIndexed { index, icon ->
            if (isPlainColorIcon(icon.bytes, icon.mimeType)) {
                plainColorPositions.add(index)
                false
            } else {
                true
            }
        }

        // If all icons in batch are plain colors, return early with nulls
        if (iconsToProcess.isEmpty()) {
            return MultiIconInterpreterOutput(
                interpretations = icons.map {
                    MultiIconInterpreterOutput.IconInterpretation(label = null)
                },
                tokenUsage = tokenUsage
            ) to tokenUsage
        }

        // Build content with all non-plain-color icons
        val contentParts = mutableListOf<Part>()

        // Add each icon as an image part with position label
        iconsToProcess.forEachIndexed { index, icon ->
            contentParts.add(Part.fromText("Icon ${index + 1}:"))
            contentParts.add(Part.fromBytes(icon.bytes, icon.mimeType.value))
        }

        // Add instruction text
        contentParts.add(Part.fromText("Interpret the above ${iconsToProcess.size} icons in order"))

        val response = retryLlmCall<MultiIconInterpretationResponse> {
            val result = client.models.generateContent(
                modelId,
                listOf(Content.fromParts(*(contentParts.toTypedArray()))),
                GenerateContentConfig.builder()
                    .temperature(0.0F)
                    .responseSchema(outputSchema)
                    .responseMimeType("application/json")
                    .thinkingConfig(
                        ThinkingConfig.builder()
                            .thinkingBudget(0)
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

        // Reconstruct full batch output list, inserting null for plain color icons
        val llmResultsIterator = response.icons.iterator()
        val interpretations = icons.indices.map { position ->
            val label = if (plainColorPositions.contains(position)) {
                null
            } else {
                llmResultsIterator.next().label
            }

            MultiIconInterpreterOutput.IconInterpretation(label = label)
        }

        return MultiIconInterpreterOutput(
            interpretations = interpretations,
            tokenUsage = tokenUsage
        ) to tokenUsage
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


