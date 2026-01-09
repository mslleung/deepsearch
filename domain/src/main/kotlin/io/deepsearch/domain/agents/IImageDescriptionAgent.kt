package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest

/**
 * Input for image description (for images without detected text).
 * Contains multiple images to be processed in parallel.
 */
data class ImageDescriptionInput(
    val images: List<ImageItem>
) : IAgent.IAgentInput {
    data class ImageItem(
        val bytes: ByteArray,
        val mimeType: ImageMimeType,
    )
}

/**
 * Output for image description.
 * Contains description results for all images that were sent in the input.
 * The order of descriptions matches the order of images in the input.
 */
data class ImageDescriptionOutput(
    val descriptions: List<ImageDescription>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput {

    data class ImageDescription(
        /**
         * A textual classification of the image type.
         * Examples: icon, logo, avatar, infographic, chart, diagram, product image,
         * photograph, portrait, headshot, screenshot, UI mockup, decorative, background.
         */
        val imageType: String,
        /**
         * The purpose or role of the image in context.
         * Examples: promotional banner, navigation element, data visualization,
         * product showcase, decorative element, profile picture, etc.
         */
        val purpose: String,
        /**
         * Description of the image that captures its visual content and meaning.
         * Detail level varies by image type and complexity:
         * - Brief for simple icons/decorative elements
         * - Detailed for photographs, charts, infographics
         */
        val description: String
    )
}

/**
 * Agent interface for describing images that do not contain detectable text.
 *
 * This agent is used when OCR detects no text in an image. It:
 * - Classifies the image type (icon, infographic, product image, portrait, etc.)
 * - Identifies the image's purpose (navigation, promotional, decorative, etc.)
 * - Generates a description with detail proportional to image complexity
 *
 * For images WITH text, use IImageClassificationAgent instead.
 */
interface IImageDescriptionAgent : IAgent<ImageDescriptionInput, ImageDescriptionOutput> {
    override suspend fun generate(input: ImageDescriptionInput): ImageDescriptionOutput

    /**
     * Prepare a batch request for image description.
     * Used by batch processing to create requests with the same prompts as interactive mode.
     *
     * @param requestId Unique identifier for this request
     * @param image Single image to describe
     * @return BatchContentRequest with multimodal content (text + image)
     */
    fun prepareBatchRequest(
        requestId: String,
        image: ImageDescriptionInput.ImageItem
    ): BatchContentRequest

    /**
     * Parse a batch response into image description.
     * Used by batch processing to parse responses with the same logic as interactive mode.
     *
     * @param responseText The JSON response from the batch API
     * @return The image description result
     */
    fun parseBatchResponse(responseText: String): ImageDescriptionOutput.ImageDescription
}
