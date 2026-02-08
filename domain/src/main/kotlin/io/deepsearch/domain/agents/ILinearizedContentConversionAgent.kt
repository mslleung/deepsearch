package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

/**
 * Input for converting hidden container HTML to linearized structured text.
 *
 * Used when spatial analysis has identified a table-like candidate inside a hidden
 * container (accordion, tab, collapsed section). The agent classifies the content
 * and outputs explicit key-value style text instead of markdown tables.
 */
data class LinearizedContentConversionInput(
    /** Outer HTML of the content element (after media placeholder resolution) */
    val html: String,
    /** Context about the content (e.g. grid dimensions, depth) */
    val auxiliaryInfo: String = "",
    /** Whether the content contains media elements (icons, images) */
    val containsMedia: Boolean = false
) : IAgent.IAgentInput

/**
 * Output of linearized content conversion.
 *
 * For TABLE/CARD: structuredText is in linearized row format (each row's values
 * labeled with column headers). For LIST: bullet/numbered text. For removable
 * classifications, structuredText may be empty.
 */
data class LinearizedContentConversionOutput(
    /** Content type classification */
    val classification: SnippetClassification,
    /** Linearized structured text (no markdown table syntax) */
    val structuredText: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput {
    val isTabular: Boolean get() = classification.isTabular()
}

/**
 * Converts hidden container HTML to linearized structured text.
 *
 * Used for non-semantic table-like content (CSS/div grids) inside hidden containers
 * where bounding box mapping is unreliable. Outputs explicit "Key: Value" or
 * "Row label: - Field: value" format instead of markdown tables for better
 * downstream retrieval and LLM understanding.
 */
interface ILinearizedContentConversionAgent : IAgent<LinearizedContentConversionInput, LinearizedContentConversionOutput> {
    override suspend fun generate(input: LinearizedContentConversionInput): LinearizedContentConversionOutput

    /**
     * Convert multiple HTML snippets in parallel.
     * Returns results in the same order as inputs.
     */
    suspend fun generateBatch(inputs: List<LinearizedContentConversionInput>): List<LinearizedContentConversionOutput>
}
