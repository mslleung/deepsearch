package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest
import kotlinx.serialization.Serializable

/**
 * Table identified on a webpage for interpretation.
 * 
 * Contains the location (CSS selector, data-ds-id) and metadata about the table.
 */
@Serializable
data class TableIdentification(
    /** CSS selector to locate the table element */
    val cssSelector: String,
    /** Stable element ID (data-ds-id) for consistent identification */
    val dataId: String,
    /** Description or auxiliary information about the table */
    val auxiliaryInfo: String = "",
    /** Whether the table contains media elements (images, icons) */
    val containsMedia: Boolean = false
)

/**
 * Classification of HTML snippet content type.
 * 
 * The LLM classifies the snippet to determine how it should be processed:
 * - TABLE: Tabular data (pricing, comparison, specifications) - converted to markdown table. DEFAULT for grid-like content.
 * - CARD: Card-like structures - converted to markdown table for structured data
 * - LIST: Bullet point or numbered lists - converted to markdown list
 * - COOKIE_DECLARATION_TABLE: Cookie consent declaration tables (legal boilerplate) - logged and removed
 * - HIDDEN_MOBILE_LAYOUT: Hidden mobile-specific layouts (duplicate content) - logged and removed
 * 
 * Note: OTHERS has been removed. Content detected as grid-like defaults to TABLE.
 */
enum class SnippetClassification {
    TABLE,
    CARD,
    LIST,
    COOKIE_DECLARATION_TABLE,
    HIDDEN_MOBILE_LAYOUT;
    
    companion object {
        fun fromString(value: String): SnippetClassification {
            // Default to TABLE for unrecognized values (content has grid structure from spatial analysis)
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: TABLE
        }
    }
    
    /** Whether this classification represents tabular content that should be rendered as a table */
    fun isTabular(): Boolean = this == TABLE || this == CARD
    
    /** Whether this classification represents content that should be removed from DOM */
    fun shouldRemoveFromDom(): Boolean = this == COOKIE_DECLARATION_TABLE || this == HIDDEN_MOBILE_LAYOUT
}

/**
 * Input for table interpretation with pre-computed data.
 * 
 * The tableHtml and boundingBoxes are derived from the page snapshot,
 * allowing the browser to be released before table interpretation begins.
 * 
 * @param tableIdentification Table metadata from identification phase
 * @param tableHtml Outer HTML of the table element
 * @param boundingBoxes Element-relative bounding boxes (XPath from table -> BoundingBox)
 */
data class TableInterpretationInput(
    val tableIdentification: TableIdentification,
    val tableHtml: String,
    val boundingBoxes: Map<String, IBrowserPage.BoundingBox>
) : IAgent.IAgentInput

data class TableInterpretationOutput(
    /** Classification of the HTML snippet content */
    val classification: SnippetClassification,
    /** The markdown representation */
    val markdown: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput {
    /** Whether this represents tabular content (TABLE or CARD) */
    val isTabular: Boolean get() = classification.isTabular()
}

/**
 * Result of parsing a table interpretation batch response.
 */
data class TableInterpretationBatchResult(
    /** Classification of the HTML snippet content */
    val classification: SnippetClassification,
    val markdown: String,
    val additionalInfo: String
) {
    /** Whether this represents tabular content (TABLE or CARD) */
    val isTabular: Boolean get() = classification.isTabular()
}

interface ITableInterpretationAgent : IAgent<TableInterpretationInput, TableInterpretationOutput> {
    override suspend fun generate(input: TableInterpretationInput): TableInterpretationOutput

    /**
     * Prepare a batch request for table interpretation.
     * Used by batch processing to create requests with the same prompts as interactive mode.
     * 
     * @param requestId Unique identifier for this request
     * @param tableHtml HTML of the table element
     * @param auxiliaryInfo Description and column headers from table identification
     * @param boundingBoxes Element bounding boxes for spatial understanding (XPath -> BoundingBox)
     */
    fun prepareBatchRequest(
        requestId: String,
        tableHtml: String,
        auxiliaryInfo: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox> = emptyMap()
    ): BatchContentRequest

    /**
     * Parse a batch response into table markdown.
     * Used by batch processing to parse responses with the same logic as interactive mode.
     */
    fun parseBatchResponse(responseText: String): TableInterpretationBatchResult
}
