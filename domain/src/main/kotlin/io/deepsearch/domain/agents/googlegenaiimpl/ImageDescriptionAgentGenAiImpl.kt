package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IImageDescriptionAgent
import io.deepsearch.domain.agents.ImageDescriptionInput
import io.deepsearch.domain.agents.ImageDescriptionOutput
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
 * Image description agent for images that do not contain detectable text.
 *
 * This agent classifies images by type, identifies their purpose, and generates
 * descriptions with detail proportional to image complexity. It uses type-specific
 * instructions to ensure appropriate handling of different image categories.
 *
 * Each image is processed individually in its own LLM call to maximize
 * extraction quality, while processing multiple images in parallel for throughput.
 */
class ImageDescriptionAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IImageDescriptionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_PIXEL_COUNT = 33_000_000L // ~33 million pixels (e.g., 6000×5500)
    }

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("image description result for images without text")
        .properties(
            mapOf(
                "imageType" to Schema.builder()
                    .type("STRING")
                    .description("A textual classification of the image type: icon, logo, avatar, infographic, chart, diagram, product image, photograph, portrait, headshot, screenshot, UI mockup, decorative, background, or other descriptive category")
                    .build(),
                "purpose" to Schema.builder()
                    .type("STRING")
                    .description("The purpose or role of the image: navigation element, promotional banner, data visualization, product showcase, decorative element, profile picture, illustration, branding, call-to-action, or other purpose")
                    .build(),
                "description" to Schema.builder()
                    .type("STRING")
                    .description("The textual description of the image")
                    .build()
            )
        )
        .required(listOf("imageType", "purpose", "description"))
        .build()

    private val systemInstruction = """
        Classify the image and provide a description for search indexing.
        
        CRITICAL: Description length should match INFORMATION DENSITY of the image.
        - Simple UI elements → very brief (2-5 words)
        - Informative content → comprehensive (describe all meaningful content)

        # Image Types
        icon, logo, photograph, portrait, product image, banner, promotional, chart, graph, infographic, diagram, screenshot, UI mockup, background, loading indicator, decorative

        # Description Rules by Type
        
        ## SIMPLE (be VERY brief, 2-5 words):
        - Icons/logos/buttons: "phone icon", "WhatsApp logo", "checkmark", "clock icon"
        - Loading indicators: "loading spinner"
        - Decorative/Background: "decorative background", "gradient pattern"
        - NEVER describe colors/pixels for simple images
        
        ## MEDIUM (1-2 sentences):
        - Photographs: "Two people smiling in dental office"
        - Product images: "Clear dental aligners in carrying case"
        - Portraits: "Professional headshot of a woman"
        
        ## COMPREHENSIVE (describe all meaningful content):
        - Charts/Graphs: Describe what the chart shows, axis labels, visible trends
          Example: "Line graph showing website traffic growth from 2020-2024"
        - Diagrams: Describe the process or relationships shown
        - Infographics: Describe the topic and key visual elements
        - Screenshots: Describe the UI and any visible data
        
        Avoid color descriptions for non-informative images

        Output: { "imageType": string, "purpose": string (2-5 words), "description": string }
    """.trimIndent()

    @Serializable
    private data class SingleImageDescriptionResponse(
        val imageType: String,
        val purpose: String,
        val description: String
    )

    override suspend fun generate(input: ImageDescriptionInput): ImageDescriptionOutput {
        logger.debug(
            "Describing {} images without text (processing each image individually in parallel)",
            input.images.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        val emptyTokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.images.isEmpty()) {
            return ImageDescriptionOutput(
                descriptions = emptyList(),
                tokenUsage = emptyTokenUsage
            )
        }

        // Process each image individually, in parallel if multiple images
        return if (input.images.size == 1) {
            // Single image - process directly
            val (description, tokenUsage) = processSingleImage(input.images[0], 0)
            ImageDescriptionOutput(
                descriptions = listOf(description),
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
    private suspend fun processImagesInParallel(images: List<ImageDescriptionInput.ImageItem>): ImageDescriptionOutput =
        coroutineScope {
            logger.debug("Processing {} images in parallel for description", images.size)

            // Process all images in parallel
            val results = images.mapIndexed { index, image ->
                async {
                    processSingleImage(image, index)
                }
            }.awaitAll()

            // Combine results in order
            val allDescriptions = results.map { it.first }
            val aggregatedTokenUsage =
                results.fold(TokenUsageMetrics.empty(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)) { acc, (_, tokenUsage) ->
                    TokenUsageMetrics(
                        modelName = acc.modelName,
                        promptTokens = acc.promptTokens + tokenUsage.promptTokens,
                        outputTokens = acc.outputTokens + tokenUsage.outputTokens,
                        totalTokens = acc.totalTokens + tokenUsage.totalTokens
                    )
                }

            ImageDescriptionOutput(
                descriptions = allDescriptions,
                tokenUsage = aggregatedTokenUsage
            )
        }

    /**
     * Process a single image with a dedicated LLM call.
     * Returns a Pair of (description, tokenUsage) for aggregation.
     */
    private suspend fun processSingleImage(
        image: ImageDescriptionInput.ImageItem,
        imageIndex: Int
    ): Pair<ImageDescriptionOutput.ImageDescription, TokenUsageMetrics> {
        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        // Check if image is too large
        if (isImageTooLarge(image.bytes, image.mimeType)) {
            logger.warn(
                "Image at position {} is too large (resolution exceeds {} pixels); skipping description",
                imageIndex,
                MAX_PIXEL_COUNT
            )
            return ImageDescriptionOutput.ImageDescription(
                imageType = "unknown",
                purpose = "unknown",
                description = ""
            ) to tokenUsage
        }

        // Build content with single image
        val contentParts = listOf(
            Part.fromBytes(image.bytes, image.mimeType.value),
            Part.fromText("Describe this image, identifying its type, purpose, and visual content")
        )

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<SingleImageDescriptionResponse>(this@ImageDescriptionAgentGenAiImpl::class.simpleName!!) {
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
        }

        val description = response.description.trim().ifBlank { "" }
        val imageType = response.imageType.trim().ifBlank { "unknown" }
        val purpose = response.purpose.trim().ifBlank { "unknown" }

        // Log description result for debugging
        logger.debug(
            "Image {} described: imageType={}, purpose={}, descriptionLength={}, descriptionPreview={}",
            imageIndex,
            imageType,
            purpose,
            description.length,
            description.take(100).replace("\n", "\\n")
        )

        return ImageDescriptionOutput.ImageDescription(
            imageType = imageType,
            purpose = purpose,
            description = description
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
        image: ImageDescriptionInput.ImageItem
    ): BatchContentRequest {
        return BatchContentRequest(
            requestId = requestId,
            modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
            systemInstruction = systemInstruction,
            userPrompt = "Describe this image, identifying its type, purpose, and visual content",
            imageData = Base64.encode(image.bytes),
            imageMimeType = image.mimeType.value,
            temperature = 1.0f
        ).withSchema(outputSchema)
    }

    override fun parseBatchResponse(responseText: String): ImageDescriptionOutput.ImageDescription {
        return try {
            val response = batchJson.decodeFromString<SingleImageDescriptionResponse>(responseText)
            val description = response.description.trim().ifBlank { "" }
            val imageType = response.imageType.trim().ifBlank { "unknown" }
            val purpose = response.purpose.trim().ifBlank { "unknown" }
            ImageDescriptionOutput.ImageDescription(
                imageType = imageType,
                purpose = purpose,
                description = description
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: {}", e.message)
            ImageDescriptionOutput.ImageDescription(
                imageType = "unknown",
                purpose = "unknown",
                description = ""
            )
        }
    }
}
