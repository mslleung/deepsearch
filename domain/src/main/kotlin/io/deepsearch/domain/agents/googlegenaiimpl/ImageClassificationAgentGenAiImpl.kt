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
import io.deepsearch.domain.services.BatchContentRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Image classification agent that classifies images by type, generates comprehensive
 * descriptions, and detects if the image contains a table.
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
        .description("image classification and description result")
        .properties(
            mapOf(
                "imageType" to Schema.builder()
                    .type("STRING")
                    .description("A textual classification of the image type: icon, logo, avatar, infographic, chart, diagram, product image, photograph, portrait, headshot, screenshot, UI mockup, decorative, background, or other descriptive category")
                    .build(),
                "imageDescription" to Schema.builder()
                    .type("STRING")
                    .description("Comprehensive description of what the image shows, written as standalone sentences/paragraphs - not just extracted text but meaningful prose describing the image content")
                    .build(),
                "containsTable" to Schema.builder()
                    .type("BOOLEAN")
                    .description("whether the image contains a table (data arranged in rows and columns)")
                    .build()
            )
        )
        .required(listOf("imageType", "imageDescription", "containsTable"))
        .build()

    private val systemInstruction = """
        You are given an image found in a webpage. Your task is to classify it and provide a comprehensive description.
        
        Classification guidelines for imageType:
        - Use descriptive categories such as: icon, logo, avatar, infographic, chart, diagram, product image, photograph, portrait, headshot, screenshot, UI mockup, decorative, background
        - Be specific: "company logo" is better than just "logo", "product photograph" is better than just "photograph"
        - You can combine categories if appropriate: "product infographic", "UI screenshot"
        
        Instructions for imageDescription:
        - Write comprehensive descriptions as standalone sentences and paragraphs
        - Don't just extract visible text - describe what the image shows and its purpose
        - If text is present in the image, incorporate it naturally into your description
        - For charts/graphs, describe the data trends and all key insights
        - For product images, describe the product's appearance and features
        - For screenshots, describe what the interface shows and its purpose
        - For portraits/headshots, describe the person's appearance professionally
        - For icons/logos, describe their visual design and likely purpose
        
        Table detection:
        - Set containsTable to true if you see tabular data (information arranged in rows and columns)
        - Set containsTable to false otherwise
        - If containsTable is true, still provide a description but note that specialized table extraction will be used
        
        Expected output shape:
        {
            "imageType": "A textual classification of the image type",
            "imageDescription": "Comprehensive description as standalone sentences/paragraphs",
            "containsTable": boolean
        }
    """.trimIndent()

    @Serializable
    private data class SingleImageClassificationResponse(
        val imageType: String,
        val imageDescription: String,
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
                imageType = "unknown",
                imageDescription = null,
                containsTable = false
            ) to tokenUsage
        }

        // Build content with single image
        val contentParts = listOf(
            Part.fromBytes(image.bytes, image.mimeType.value),
            Part.fromText("Classify this image and provide a comprehensive description")
        )

        val response = retryLlmCall<SingleImageClassificationResponse>(this::class.simpleName!!) {
            val result = client.models.generateContent(
                modelId,
                listOf(Content.fromParts(*(contentParts.toTypedArray()))),
                GenerateContentConfig.builder()
                    .temperature(1.0F)
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

        val description = response.imageDescription.takeIf { it.isNotBlank() }?.trim()
        val imageType = response.imageType.trim().ifBlank { "unknown" }

        // Log classification result for debugging
        logger.debug(
            "Image {} classified: imageType={}, containsTable={}, descriptionLength={}, descriptionPreview={}",
            imageIndex,
            imageType,
            response.containsTable,
            description?.length ?: 0,
            description?.take(100)?.replace("\n", "\\n") ?: "<null>"
        )

        return ImageClassificationOutput.ImageClassification(
            imageType = imageType,
            imageDescription = description,
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

    // ========== Batch Processing Methods ==========

    private val batchJson = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalEncodingApi::class)
    override fun prepareBatchRequest(
        requestId: String,
        image: ImageClassificationInput.ImageItem
    ): BatchContentRequest {
        return BatchContentRequest(
            requestId = requestId,
            modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
            systemInstruction = systemInstruction,
            userPrompt = "Classify this image and provide a comprehensive description",
            imageData = Base64.encode(image.bytes),
            imageMimeType = image.mimeType.value,
            temperature = 1.0f
        ).withSchema(outputSchema) // Use same schema as interactive mode
    }

    override fun parseBatchResponse(responseText: String): ImageClassificationOutput.ImageClassification {
        return try {
            val response = batchJson.decodeFromString<SingleImageClassificationResponse>(responseText)
            val description = response.imageDescription.takeIf { it.isNotBlank() }?.trim()
            val imageType = response.imageType.trim().ifBlank { "unknown" }
            ImageClassificationOutput.ImageClassification(
                imageType = imageType,
                imageDescription = description,
                containsTable = response.containsTable
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: {}", e.message)
            ImageClassificationOutput.ImageClassification(
                imageType = "unknown",
                imageDescription = null,
                containsTable = false
            )
        }
    }
}
