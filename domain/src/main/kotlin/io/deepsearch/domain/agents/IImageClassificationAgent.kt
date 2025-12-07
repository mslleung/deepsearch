package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

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
 * Output for image classification and text extraction.
 * Contains classification results for all images that were sent in the input.
 * The order of classifications matches the order of images in the input.
 */
data class ImageClassificationOutput(
    val classifications: List<ImageClassification>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput {
    
    /**
     * Represents the type/purpose of an image on a webpage.
     */
    enum class ImageType {
        /**
         * Decorative images, icons, photographs used for visual appeal.
         * These don't contain meaningful text data to extract.
         */
        ILLUSTRATIVE,
        
        /**
         * Images containing text, data, charts, or tables.
         * These contain information that should be extracted.
         */
        INFORMATIONAL
    }
    
    data class ImageClassification(
        /**
         * Whether the image is illustrative (decorative) or informational (contains data/text).
         */
        val imageType: ImageType,
        /**
         * Extracted text from the image (description for illustrative images,
         * text content for informational images without tables).
         * This text should NOT be used if containsTable is true.
         */
        val text: String?,
        /**
         * Whether the image contains a table.
         * If true, the text field should be discarded and a specialized
         * table extraction agent should be invoked instead.
         */
        val containsTable: Boolean
    )
}

/**
 * Agent interface for classifying images and extracting text.
 *
 * This agent:
 * - Identifies whether an image is illustrative or informational
 * - Extracts text from the image (description for illustrative, content for informational)
 * - Returns a flag indicating whether the image contains a table
 *
 * If containsTable is true, the extracted text should be discarded and
 * a specialized table extraction agent should be used instead.
 */
interface IImageClassificationAgent : IAgent<ImageClassificationInput, ImageClassificationOutput> {
    override suspend fun generate(input: ImageClassificationInput): ImageClassificationOutput
}

