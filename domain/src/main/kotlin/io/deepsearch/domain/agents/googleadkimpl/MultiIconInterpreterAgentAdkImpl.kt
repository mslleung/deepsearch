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
import io.deepsearch.domain.agents.IMultiIconInterpreterAgent
import io.deepsearch.domain.agents.MultiIconInterpreterInput
import io.deepsearch.domain.agents.MultiIconInterpreterOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
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
class MultiIconInterpreterAgentAdkImpl : IMultiIconInterpreterAgent {

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

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("multiIconInterpreterAgent")
        description("Interpret multiple UI icon images and output concise labels for each")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.0F)
                .thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(0)
                        .build()
                )
                .build()
        )
        instruction(
            """
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
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

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

        if (input.icons.isEmpty()) {
            return MultiIconInterpreterOutput(
                interpretations = emptyList(),
                tokenUsage = TokenUsageMetrics.empty(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
            )
        }

        // Split icons into batches of BATCH_SIZE and process in parallel
        return if (input.icons.size <= BATCH_SIZE) {
            // Single batch - process directly
            processBatch(input.icons)
        } else {
            // Multiple batches - process in parallel
            processInParallelBatches(input.icons)
        }
    }

    /**
     * Process multiple batches of icons in parallel.
     */
    private suspend fun processInParallelBatches(icons: List<MultiIconInterpreterInput.IconItem>): MultiIconInterpreterOutput = coroutineScope {
        val batches = icons.chunked(BATCH_SIZE)
        logger.debug("Processing {} icons in {} parallel batches", icons.size, batches.size)

        // Process all batches in parallel
        val batchResults = batches.map { batch ->
            async {
                processBatch(batch)
            }
        }.awaitAll()

        // Combine results from all batches in order
        val allInterpretations = batchResults.flatMap { it.interpretations }
        MultiIconInterpreterOutput(
            interpretations = allInterpretations,
            tokenUsage = TokenUsageMetrics.empty(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        )
    }

    /**
     * Process a single batch of icons (up to BATCH_SIZE).
     */
    private suspend fun processBatch(icons: List<MultiIconInterpreterInput.IconItem>): MultiIconInterpreterOutput {
        logger.debug("Processing batch of {} icons", icons.size)

        // Pre-filter plain color icons to save on LLM calls
        // Track which positions in the batch are plain color
        val plainColorPositions = mutableSetOf<Int>()
        val iconsToProcess = icons.filterIndexed { index, icon ->
            if (isPlainColourIcon(icon.bytes, icon.mimeType)) {
                logger.debug("Detected plain-colour icon at batch position {}; will return null label", index)
                plainColorPositions.add(index)
                false
            } else {
                true
            }
        }

        // If all icons in batch are plain color, return early
        if (iconsToProcess.isEmpty()) {
            return MultiIconInterpreterOutput(
                interpretations = icons.map {
                    MultiIconInterpreterOutput.IconInterpretation(label = null)
                },
                tokenUsage = TokenUsageMetrics.empty(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
            )
        }

        // Build content with all images
        val contentParts = mutableListOf<Part>()
        
        // Add instruction text
        contentParts.add(Part.fromText("Interpret the following ${iconsToProcess.size} icons in order:"))
        
        // Add each icon as an image part with position label
        iconsToProcess.forEachIndexed { index, icon ->
            contentParts.add(Part.fromText("Icon ${index + 1}:"))
            contentParts.add(Part.fromBytes(icon.bytes, icon.mimeType.value))
        }

        val response = retryLlmCall<MultiIconInterpretationResponse> {
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
                Content.fromParts(*(contentParts.toTypedArray())),
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

        // Format the labels from LLM response
        val formattedLabels = response.icons.map { iconResponse ->
            iconResponse.label?.let { rawLabel ->
                if (rawLabel.isNotBlank()) {
                    formatIconLabel(rawLabel)
                } else {
                    null
                }
            }
        }

        // Reconstruct full batch output list, inserting LLM results at non-plain-color positions
        val llmResultsIterator = formattedLabels.iterator()
        val interpretations = icons.indices.map { position ->
            val label = if (plainColorPositions.contains(position)) {
                null
            } else {
                llmResultsIterator.next()
            }

            if (label != null) {
                logger.debug("Icon at batch position {} interpreted: {}", position, label)
            } else {
                logger.debug("Icon at batch position {} cannot be interpreted ({} bytes)", position, icons[position].bytes.size)
            }

            MultiIconInterpreterOutput.IconInterpretation(label = label)
        }

        return MultiIconInterpreterOutput(
            interpretations = interpretations,
            tokenUsage = TokenUsageMetrics.empty(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        )
    }

    /**
     * Detects if the provided icon image is effectively a solid, uniform colour.
     * Plain colour icons carry no semantic meaning for UI intent, so they are skipped.
     */
    private fun isPlainColourIcon(bytes: ByteArray, @Suppress("UNUSED_PARAMETER") mimeType: ImageMimeType): Boolean {
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
            logger.warn("Failed to inspect image for uniform colour, proceeding with LLM", e)
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

