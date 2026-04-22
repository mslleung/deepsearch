package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

/**
 * Input for the unified multimodal table conversion agent.
 *
 * Combines both a cropped screenshot of the table region and the cleaned HTML snippet
 * from the DOM. The agent uses the image as visual ground truth for structure and the
 * HTML as the primary text source to avoid OCR errors.
 *
 * @param regionImage Cropped PNG screenshot of the table region
 * @param imageMimeType MIME type of the region image (typically PNG)
 * @param cleanedHtml Cleaned HTML snippet from DOM extraction; null or blank for purely graphical tables
 * @param auxiliaryInfo Context about the table (e.g., relevance description from the navigation agent)
 */
data class AgenticTableConversionInput(
    val regionImage: ByteArray,
    val imageMimeType: ImageMimeType,
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
