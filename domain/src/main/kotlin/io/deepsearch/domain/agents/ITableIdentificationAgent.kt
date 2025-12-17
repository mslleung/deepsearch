package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
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

interface ITableIdentificationAgent : IAgent<TableIdentificationInput, TableIdentificationOutput> {
    override suspend fun generate(input: TableIdentificationInput): TableIdentificationOutput
}
