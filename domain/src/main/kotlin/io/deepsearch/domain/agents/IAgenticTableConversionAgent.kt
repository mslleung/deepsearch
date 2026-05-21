package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class TableSubRegionImage(
    val bytes: ByteArray,
    val mimeType: ImageMimeType,
    val role: TableRegionRole,
    val description: String
)

/**
 * Input for the unified multimodal table conversion agent.
 *
 * Each table sub-region (header, data, context) is cropped individually and passed
 * with its role label. The agent synthesizes them into a single coherent HTML table.
 * The cleaned HTML snippet from DOM extraction is provided as a supplementary text source.
 *
 * @param subRegionImages Individually cropped images for each table sub-region, labeled with role
 * @param cleanedHtml Cleaned HTML snippet from DOM extraction; null or blank for purely graphical tables
 * @param auxiliaryInfo Context about the table (e.g., relevance description from the navigation agent)
 */
data class AgenticTableConversionInput(
    val subRegionImages: List<TableSubRegionImage>,
    val cleanedHtml: String?,
    val auxiliaryInfo: String
) : IAgent.IAgentInput

/**
 * Output from the unified multimodal table conversion agent.
 *
 * Contains a clean HTML table string with proper structure (thead/tbody, colspan/rowspan).
 * The caller is responsible for converting this to markdown via [TableMarkdownUtils].
 */
data class AgenticTableConversionOutput(
    val htmlTable: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Multimodal agent that converts a table region into clean HTML table markup.
 *
 * Takes both a cropped screenshot and the DOM-extracted HTML snippet, using the image
 * as visual ground truth for table structure/dimensions and the HTML as the text source.
 * When no useful HTML is available (graphical tables rendered as images), falls back to
 * OCR from the screenshot.
 */
interface IAgenticTableConversionAgent : IAgent<AgenticTableConversionInput, AgenticTableConversionOutput>
