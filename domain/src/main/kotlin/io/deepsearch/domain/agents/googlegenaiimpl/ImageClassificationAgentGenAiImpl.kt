package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IImageClassificationAgent
import io.deepsearch.domain.agents.ImageClassificationInput
import io.deepsearch.domain.agents.ImageClassificationOutput
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
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Image classification agent that identifies whether images are illustrative or informational,
 * extracts text, and detects if the image contains a table.
 *
 * This agent processes each image individually in its own LLM call to maximize
 * extraction quality, while processing multiple images in parallel for throughput.
 */
class ImageClassificationAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IImageClassificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_PIXEL_COUNT = 33_000_000L // ~33 million pixels (e.g., 6000×5500)
    }

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("image classification and text extraction result")
        .properties(
            mapOf(
                "imageType" to Schema.builder()
                    .type("STRING")
                    .description("whether the image is ILLUSTRATIVE (decorative, icons, photos for visual appeal) or INFORMATIONAL (contains text, data, charts, tables)")
                    .enum_(listOf("ILLUSTRATIVE", "INFORMATIONAL"))
                    .build(),
                "text" to Schema.builder()
                    .type("STRING")
                    .description("text representation of the image")
                    .nullable(true)
                    .build(),
                "containsTable" to Schema.builder()
                    .type("BOOLEAN")
                    .description("whether the image contains a table")
                    .build()
            )
        )
        .required(listOf("imageType", "text", "containsTable"))
        .build()

    private val systemInstruction = """
        You are given an image found in a webpage. Your task is to classify it and extract text.
        
        Images on webpages can serve two purposes:
        1. ILLUSTRATIVE: decorative images, icons, logos, photographs used for visual appeal, backgrounds, avatars
        2. INFORMATIONAL: images containing meaningful text, data, charts, diagrams, or tables
        
        Classification guidelines:
        - Use ILLUSTRATIVE for: photos of people/places/objects, decorative graphics, icons, logos, avatars, background images
        - Use INFORMATIONAL for: screenshots with text, infographics, charts, tables, diagrams with labels, images with significant readable text
        
        Instructions for ILLUSTRATIVE images:
        - Generate a brief description of what the image shows
        - Set containsTable to false
        
        Instructions for INFORMATIONAL images:
        - Extract all text present in the image, with reasonable line breaks
        - Identify if the image contains a table (data arranged in rows and columns)
        - Set containsTable to true if you see tabular data, false otherwise
        - If containsTable is true, you don't need to format the table specially - just note that it exists
        
        Expected output shape:
        {
            "imageType": "ILLUSTRATIVE" | "INFORMATIONAL",
            "text": string | null,
            "containsTable": boolean
        }
    """.trimIndent()

    @Serializable
    private data class SingleImageClassificationResponse(
        val imageType: String,
        val text: String?,
        val containsTable: Boolean
    )

    override suspend fun generate(input: ImageClassificationInput): ImageClassificationOutput {
        logger.debug(
            "Classifying {} images (processing each image individually in parallel)",
            input.images.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        val emptyTokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.images.isEmpty()) {
            return ImageClassificationOutput(
                classifications = emptyList(),
                tokenUsage = emptyTokenUsage
            )
        }

        // Process each image individually, in parallel if multiple images
        return if (input.images.size == 1) {
            // Single image - process directly
            val (classification, tokenUsage) = processSingleImage(input.images[0], 0)
            ImageClassificationOutput(
                classifications = listOf(classification),
                tokenUsage = tokenUsage
            )
        } else {
            // Multiple images - process in parallel
            processImagesInParallel(input.images)
        }
    }

    /**
     * Process multiple images in parallel, each with its own LLM call.
     */
    private suspend fun processImagesInParallel(images: List<ImageClassificationInput.ImageItem>): ImageClassificationOutput =
        coroutineScope {
            logger.debug("Processing {} images in parallel", images.size)

            // Process all images in parallel
            val results = images.mapIndexed { index, image ->
                async {
                    processSingleImage(image, index)
                }
            }.awaitAll()

            // Combine results in order
            val allClassifications = results.map { it.first }
            val aggregatedTokenUsage =
                results.fold(TokenUsageMetrics.empty(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)) { acc, (_, tokenUsage) ->
                    TokenUsageMetrics(
                        modelName = acc.modelName,
                        promptTokens = acc.promptTokens + tokenUsage.promptTokens,
                        outputTokens = acc.outputTokens + tokenUsage.outputTokens,
                        totalTokens = acc.totalTokens + tokenUsage.totalTokens
                    )
                }

            ImageClassificationOutput(
                classifications = allClassifications,
                tokenUsage = aggregatedTokenUsage
            )
        }

    /**
     * Process a single image with a dedicated LLM call.
     * Returns a Pair of (classification, tokenUsage) for aggregation.
     */
    private suspend fun processSingleImage(
        image: ImageClassificationInput.ImageItem,
        imageIndex: Int
    ): Pair<ImageClassificationOutput.ImageClassification, TokenUsageMetrics> {
        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        // Check if image is too large
        if (isImageTooLarge(image.bytes, image.mimeType)) {
            logger.warn(
                "Image at position {} is too large (resolution exceeds {} pixels); skipping classification",
                imageIndex,
                MAX_PIXEL_COUNT
            )
            return ImageClassificationOutput.ImageClassification(
                imageType = ImageClassificationOutput.ImageType.ILLUSTRATIVE,
                text = null,
                containsTable = false
            ) to tokenUsage
        }

        // Build content with single image
        val contentParts = listOf(
            Part.fromBytes(image.bytes, image.mimeType.value),
            Part.fromText("Classify this image and extract any text content")
        )

        val response = retryLlmCall<SingleImageClassificationResponse>(this::class.simpleName!!) {
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
                    .maxOutputTokens(8192)
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

        val extractedText = response.text?.takeIf { it.isNotBlank() }?.trim()
        
        // Parse imageType from response, defaulting to INFORMATIONAL if unrecognized
        val imageType = when (response.imageType.uppercase()) {
            "ILLUSTRATIVE" -> ImageClassificationOutput.ImageType.ILLUSTRATIVE
            "INFORMATIONAL" -> ImageClassificationOutput.ImageType.INFORMATIONAL
            else -> {
                logger.warn(
                    "Unrecognized imageType '{}' for image at position {}, defaulting to INFORMATIONAL",
                    response.imageType,
                    imageIndex
                )
                ImageClassificationOutput.ImageType.INFORMATIONAL
            }
        }

        // Log classification result for debugging
        logger.debug(
            "Image {} classified: imageType={}, containsTable={}, textLength={}, textPreview={}",
            imageIndex,
            imageType,
            response.containsTable,
            extractedText?.length ?: 0,
            extractedText?.take(100)?.replace("\n", "\\n") ?: "<null>"
        )

        return ImageClassificationOutput.ImageClassification(
            imageType = imageType,
            text = extractedText,
            containsTable = response.containsTable
        ) to tokenUsage
    }

    /**
     * Detects if the provided image resolution is ridiculously large.
     * Such images could cause performance issues or errors.
     */
    private fun isImageTooLarge(bytes: ByteArray, @Suppress("UNUSED_PARAMETER") mimeType: ImageMimeType): Boolean {
        return try {
            val bufferedImage = ImageIO.read(ByteArrayInputStream(bytes)) ?: return false
            val width = bufferedImage.width
            val height = bufferedImage.height
            val pixelCount = width.toLong() * height.toLong()
            pixelCount > MAX_PIXEL_COUNT
        } catch (e: Exception) {
            logger.warn("Failed to check image size, proceeding with LLM", e)
            false
        }
    }
}

