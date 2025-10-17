package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType

/**
 * Input for multi-image text extraction.
 * Contains multiple images to be processed in a single LLM call (or multiple parallel calls if needed).
 */
data class MultiImageTextExtractionInput(
    val images: List<ImageItem>
) : IAgent.IAgentInput {
    data class ImageItem(
        val bytes: ByteArray,
        val mimeType: ImageMimeType,
    )
}

/**
 * Output for multi-image text extraction.
 * Contains extracted text for all images that were sent in the input.
 * The order of extractions matches the order of images in the input.
 */
data class MultiImageTextExtractionOutput(
    val extractions: List<TextExtraction>
) : IAgent.IAgentOutput {
    data class TextExtraction(
        val extractedText: String?
    )
}

/**
 * Agent interface for extracting text from multiple images in batched LLM calls.
 * This is more efficient than calling ImageTextExtractionAgent multiple times,
 * as it reduces the number of API calls and helps avoid rate limits.
 *
 * The agent processes images in batches of up to 50. For larger inputs, multiple
 * batches are processed in parallel to maximize throughput.
 *
 * The output extractions list will have the same size and order as the input images list,
 * making it easy to match results back to the original images by position.
 */
interface IMultiImageTextExtractionAgent : IAgent<MultiImageTextExtractionInput, MultiImageTextExtractionOutput> {
    override suspend fun generate(input: MultiImageTextExtractionInput): MultiImageTextExtractionOutput
}

