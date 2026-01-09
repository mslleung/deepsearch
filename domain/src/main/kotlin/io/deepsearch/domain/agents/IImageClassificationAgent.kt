package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest

/**
 * Input for image classification and text extraction.
 * Contains multiple images to be processed in parallel.
 */
data class ImageClassificationInput(
    val images: List<ImageItem>
) : IAgent.IAgentInput {
    data class ImageItem(
        val bytes: ByteArray,
        val mimeType: ImageMimeType,
    )
}

/**
 * Output for image classification and description.
 * Contains classification results for all images that were sent in the input.
 * The order of classifications matches the order of images in the input.
 */
data class ImageClassificationOutput(
    val classifications: List<ImageClassification>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput {
    
    data class ImageClassification(
        /**
         * A textual classification of the image type.
         * Examples: icon, logo, avatar, infographic, chart, diagram, product image,
         * photograph, portrait, headshot, screenshot, UI mockup, decorative, background.
         */
        val imageType: String,
        /**
         * Comprehensive description of what the image shows, interpreting any visible
         * text in context and converting the image content to coherent prose.
         * This field should NOT be used if needsTableInterpretation is true.
         */
        val imageDescription: String?,
        /**
         * Whether the image contains a table that needs specialized extraction.
         * Only true for tables containing important/actionable data.
         * False for illustrative tables (e.g., a teacher pointing at a whiteboard with dummy text).
         * If true, the imageDescription field should be discarded and a specialized
         * table extraction agent should be invoked instead.
         */
        val needsTableInterpretation: Boolean
    )
}

/**
 * Agent interface for classifying images with text and generating descriptions.
 *
 * This agent is used when OCR detects text in an image. It:
 * - Classifies the image type (icon, infographic, product image, portrait, etc.)
 * - Generates a comprehensive description interpreting visible text in context
 * - Returns a flag indicating whether the image needs specialized table extraction
 *
 * If needsTableInterpretation is true, the imageDescription should be discarded and
 * a specialized table extraction agent should be used instead.
 *
 * For images WITHOUT text, use IImageDescriptionAgent instead.
 */
interface IImageClassificationAgent : IAgent<ImageClassificationInput, ImageClassificationOutput> {
    override suspend fun generate(input: ImageClassificationInput): ImageClassificationOutput

    /**
     * Prepare a batch request for image classification.
     * Used by batch processing to create requests with the same prompts as interactive mode.
     * 
     * @param requestId Unique identifier for this request
     * @param image Single image to classify
     * @return BatchContentRequest with multimodal content (text + image)
     */
    fun prepareBatchRequest(
        requestId: String,
        image: ImageClassificationInput.ImageItem
    ): BatchContentRequest

    /**
     * Parse a batch response into image classification.
     * Used by batch processing to parse responses with the same logic as interactive mode.
     * 
     * @param responseText The JSON response from the batch API
     * @return The image classification result
     */
    fun parseBatchResponse(responseText: String): ImageClassificationOutput.ImageClassification
}
