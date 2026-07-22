package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.IImageClassificationAgent
import io.deepsearch.domain.agents.ImageClassificationInput
import io.deepsearch.domain.agents.ImageClassificationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Image classification agent for images that contain text.
 *
 * This agent classifies images by type, interprets visible text in context to generate
 * coherent prose descriptions, and determines if the image contains important tabular
 * data that needs specialized extraction.
 *
 * For images WITHOUT text, use ImageDescriptionAgentGenAiImpl instead.
 *
 * Each image is processed individually in its own LLM call to maximize
 * extraction quality, while processing multiple images in parallel for throughput.
 */
class ImageClassificationAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IImageClassificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_PIXEL_COUNT = 33_000_000L // ~33 million pixels (e.g., 6000×5500)
    }

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("image classification and description result for images containing text")
        .properties(
            mapOf(
                "imageType" to Schema.builder()
                    .type("STRING")
                    .description("A textual classification of the image type: icon, logo, avatar, infographic, chart, diagram, product image, photograph, portrait, headshot, screenshot, UI mockup, decorative, background, or other descriptive category")
                    .build(),
                "imageDescription" to Schema.builder()
                    .type("STRING")
                    .description("The textual description of the image")
                    .build(),
                "needsTableInterpretation" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether the image contains a table with important/actionable data that needs specialized extraction. False for illustrative or decorative tables.")
                    .build()
            )
        )
        .required(listOf("imageType", "imageDescription", "needsTableInterpretation"))
        .build()

    private val systemInstruction = """
        Classify the image and extract text/meaning for search indexing.
        
        CRITICAL: Description length should match INFORMATION DENSITY of the image.
        - Simple UI elements → very brief (2-5 words)
        - Informative content → comprehensive (extract all data)

        # Image Types
        icon, logo, photograph, portrait, product image, banner, promotional, chart, graph, infographic, diagram, screenshot, UI mockup, background, loading indicator, decorative

        # Description Rules by Type
        
        ## SIMPLE (be VERY brief, 2-5 words):
        - Icons/logos/buttons: "phone icon", "WhatsApp logo", "checkmark"
        - Loading indicators: "loading spinner"
        - Decorative: "decorative background"
        - NEVER describe colors/pixels for simple images
        
        ## MEDIUM (1-2 sentences):
        - Photographs: "Two people consulting in dental office"
        - Product images: "Clear dental aligners in carrying case"
        - Banners: "Student discount promotion - $500 off with valid ID"
        
        ## COMPREHENSIVE (extract ALL data):
        - Charts/Graphs: Extract axis labels, data points, trends, legend
          Example: "Bar chart showing monthly sales: Jan $10K, Feb $15K, Mar $22K, Apr $18K. Y-axis: Revenue in USD. Trend: Q1 growth 120%."
        - Infographics: Extract all text, statistics, key points
        - Data visualizations: Capture numbers, labels, relationships
        - Comparison tables: List all items being compared with their values
        
        ## TEXT IN IMAGE: Always extract verbatim
        - Include all readable text that conveys meaning
        
        # Table Flag
        needsTableInterpretation = TRUE for:
        - Images containing tabular data (pricing tables, spec sheets, schedules)
        - Comparison charts with structured rows/columns
        needsTableInterpretation = FALSE for:
        - Line/bar/pie charts (these are described in imageDescription)
        - Decorative or illustrative tables

        Avoid color/graphical descriptions for non-informative images

        Output: { "imageType": string, "imageDescription": string, "needsTableInterpretation": boolean }
    """.trimIndent()

    @Serializable
    private data class SingleImageClassificationResponse(
        val imageType: String,
        val imageDescription: String,
        val needsTableInterpretation: Boolean
    )

    override suspend fun generate(input: ImageClassificationInput): ImageClassificationOutput {
        logger.debug(
            "Classifying {} images (processing each image individually in parallel)",
            input.images.size
        )

        val modelId = ModelIds.GEMINI_3_5_FLASH_LITE.modelId
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
                results.fold(TokenUsageMetrics.empty(ModelIds.GEMINI_3_5_FLASH_LITE.modelId)) { acc, (_, tokenUsage) ->
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
        val modelId = ModelIds.GEMINI_3_5_FLASH_LITE.modelId
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
                needsTableInterpretation = false
            ) to tokenUsage
        }

        // Build content with single image
        val contentParts = listOf(
            Part.fromText("Classify this image and provide a comprehensive description"),
            Part.fromBytes(image.bytes, image.mimeType.value)
        )

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<SingleImageClassificationResponse>(this@ImageClassificationAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(*(contentParts.toTypedArray()))),
                    GenerateContentConfig.builder()
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
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
        }

        val description = response.imageDescription.takeIf { it.isNotBlank() }?.trim()
        val imageType = response.imageType.trim().ifBlank { "unknown" }

        // Log classification result for debugging
        logger.debug(
            "Image {} classified: imageType={}, needsTableInterpretation={}, descriptionLength={}, descriptionPreview={}",
            imageIndex,
            imageType,
            response.needsTableInterpretation,
            description?.length ?: 0,
            description?.take(100)?.replace("\n", "\\n") ?: "<null>"
        )

        return ImageClassificationOutput.ImageClassification(
            imageType = imageType,
            imageDescription = description,
            needsTableInterpretation = response.needsTableInterpretation
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
            modelId = ModelIds.GEMINI_3_5_FLASH_LITE.modelId,
            systemInstruction = systemInstruction,
            userPrompt = "Classify this image and provide a comprehensive description",
            imageData = Base64.encode(image.bytes),
            imageMimeType = image.mimeType.value
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
                needsTableInterpretation = response.needsTableInterpretation
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: {}", e.message)
            ImageClassificationOutput.ImageClassification(
                imageType = "unknown",
                imageDescription = null,
                needsTableInterpretation = false
            )
        }
    }
}
