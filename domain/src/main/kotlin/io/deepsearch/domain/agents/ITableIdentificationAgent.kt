package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest
import kotlinx.serialization.Serializable

/**
 * Input for table identification.
 * 
 * Only requires the pre-captured page snapshot - no live browser needed.
 * The browser can be released before table identification begins.
 */
data class TableIdentificationInput(
    /** Pre-captured page snapshot containing HTML and bounding boxes (without media). */
    val pageSnapshot: IBrowserPage.PageSnapshotWithMetadata
) : IAgent.IAgentInput

@Serializable
data class TableIdentification(
    /** CSS selector for initial lookup (may be position-based). */
    val cssSelector: String,
    /** Stable data-ds-id value for subsequent operations (e.g., "ds-table-5"). */
    val dataId: String,
    val auxiliaryInfo: String,
    /** Whether this table contains media (icons or images) that need interpretation first. */
    val containsMedia: Boolean = false
)

data class TableIdentificationOutput(
    val tables: List<TableIdentification>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Prepared batch request for table identification.
 * Contains the request to submit and the HTML with injected IDs for response parsing.
 */
data class TableIdentificationBatchRequest(
    val request: BatchContentRequest,
    val htmlWithIds: String
)

interface ITableIdentificationAgent : IAgent<TableIdentificationInput, TableIdentificationOutput> {
    override suspend fun generate(input: TableIdentificationInput): TableIdentificationOutput

    /**
     * Prepare a batch request for table identification.
     * Used by batch processing to create requests with the same prompts as interactive mode.
     */
    fun prepareBatchRequest(requestId: String, html: String): TableIdentificationBatchRequest

    /**
     * Parse a batch response into table identifications.
     * Used by batch processing to parse responses with the same logic as interactive mode.
     * 
     * @param responseText JSON response from batch API
     * @param htmlWithIds HTML with injected IDs for CSS selector construction
     * @param metadata Optional metadata from the batch request (contains programmaticTables for merging)
     */
    fun parseBatchResponse(
        responseText: String,
        htmlWithIds: String,
        metadata: Map<String, String>? = null
    ): List<TableIdentification>
}
