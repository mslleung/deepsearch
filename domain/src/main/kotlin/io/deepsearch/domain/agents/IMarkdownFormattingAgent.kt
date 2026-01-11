package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

/**
 * Input for markdown formatting.
 * 
 * Contains the raw extracted text from a webpage along with metadata
 * needed to produce a well-structured standalone markdown document.
 */
data class MarkdownFormattingInput(
    /** Raw extracted text content from the webpage. */
    val rawText: String,
    /** URL of the webpage. */
    val url: String,
    /** Title of the webpage (may be null). */
    val title: String?,
    /** Meta description of the webpage (may be null). */
    val description: String?,
    /** Extracted popup text (may be null). */
    val popupText: String?
) : IAgent.IAgentInput

/**
 * Output from markdown formatting.
 * 
 * Contains the formatted markdown document and token usage metrics.
 */
data class MarkdownFormattingOutput(
    /** Well-formatted standalone markdown document. */
    val markdown: String,
    /** Token usage metrics for the LLM call. */
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent for formatting raw extracted webpage content into a well-structured markdown document.
 * 
 * This agent takes raw text extracted from a webpage (after DOM processing, table interpretation,
 * and media interpretation) and formats it into a clean, readable markdown document with:
 * - Proper heading hierarchy
 * - Well-structured paragraphs
 * - Preserved lists and tables
 * - Identified code blocks
 * - Removed redundant content
 */
interface IMarkdownFormattingAgent : IAgent<MarkdownFormattingInput, MarkdownFormattingOutput> {
    override suspend fun generate(input: MarkdownFormattingInput): MarkdownFormattingOutput
}
