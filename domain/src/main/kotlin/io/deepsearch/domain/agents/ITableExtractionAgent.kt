package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

/**
 * Input for table extraction from images.
 * Contains multiple images known to contain tables.
 */
data class TableExtractionInput(
    val images: List<ImageItem>
) : IAgent.IAgentInput {
    data class ImageItem(
        val bytes: ByteArray,
        val mimeType: ImageMimeType,
    )
}

/**
 * Output for table extraction.
 * Contains extracted text (with tables in markdown format) for all images.
 * The order of extractions matches the order of images in the input.
 */
data class TableExtractionOutput(
    val extractions: List<TextExtraction>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput {
    data class TextExtraction(
        /**
         * Extracted text with tables converted to markdown format.
         */
        val extractedText: String?
    )
}

/**
 * Agent interface for extracting tables from images.
 *
 * This specialized agent is designed to extract tabular data from images
 * that are known to contain tables. It outputs tables in markdown format.
 *
 * Should only be invoked for images where a classification agent has
 * determined containsTable = true.
 */
interface ITableExtractionAgent : IAgent<TableExtractionInput, TableExtractionOutput> {
    override suspend fun generate(input: TableExtractionInput): TableExtractionOutput
}

