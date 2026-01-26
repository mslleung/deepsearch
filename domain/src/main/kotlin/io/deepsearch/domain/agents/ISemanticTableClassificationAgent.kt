package io.deepsearch.domain.agents

import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

/**
 * Input for semantic table classification.
 * 
 * @param markdownTable The markdown table to classify
 * @param auxiliaryInfo Optional context about the table (e.g., caption, summary)
 */
data class SemanticTableClassificationInput(
    val markdownTable: String,
    val auxiliaryInfo: String = ""
)

/**
 * Output from semantic table classification.
 * 
 * @param classification The determined classification (TABLE, COOKIE_DECLARATION_TABLE, etc.)
 */
data class SemanticTableClassificationOutput(
    val classification: SnippetClassification
)

/**
 * Batch result for semantic table classification.
 */
data class SemanticTableClassificationBatchResult(
    val classifications: List<SnippetClassification>,
    val tokenUsage: TokenUsageMetrics
)

/**
 * Agent that classifies markdown tables to determine their content type.
 * 
 * This is a lightweight LLM classification that takes already-converted markdown
 * (from SemanticTableConverter) and determines if it's:
 * - TABLE: Regular data table (keep in output)
 * - COOKIE_DECLARATION_TABLE: Cookie consent table (remove from output)
 * - HIDDEN_MOBILE_LAYOUT: Duplicate mobile content (remove from output)
 * - OTHERS: Non-tabular content (keep in output)
 * 
 * The CARD and LIST classifications are not used here since we're classifying
 * semantic HTML tables which always have proper table structure.
 */
interface ISemanticTableClassificationAgent {
    /**
     * Classify a batch of markdown tables in a single LLM call.
     * 
     * @param inputs List of tables to classify with optional auxiliary info
     * @return Classification for each input table in the same order
     */
    suspend fun classifyTables(inputs: List<SemanticTableClassificationInput>): SemanticTableClassificationBatchResult
}
