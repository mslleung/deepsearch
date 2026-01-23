package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest
import kotlinx.serialization.Serializable

/**
 * Input for table identification.
 * 
 * Uses vision-based detection for visible content (from screenshot).
 * 
 * Note: Hidden container table detection is now done separately using TableGridDetector
 * in WebpageExtractionService, not by this agent.
 * 
 * The browser can be released before table identification begins.
 */
data class TableIdentificationInput(
    /** Pre-captured page snapshot containing HTML and bounding boxes. */
    val pageSnapshot: IBrowserPage.PageSnapshotWithMetadata,
    /** Full-page screenshot for vision-based table detection (required). */
    val screenshot: IBrowserPage.Screenshot
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
     * 
     * @param requestId Unique request ID for batch tracking
     * @param html Raw HTML content
     * @param screenshotBase64 Optional base64-encoded screenshot for vision-based detection
     * @param screenshotMimeType Optional MIME type for screenshot (e.g., "image/png")
     * @param boundingBoxes Optional element bounding boxes for vision IoU mapping
     * @param pageWidth Optional page width in pixels for vision mapping
     * @param pageHeight Optional page height in pixels for vision mapping
     */
    fun prepareBatchRequest(
        requestId: String,
        html: String,
        screenshotBase64: String? = null,
        screenshotMimeType: String? = null,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>? = null,
        pageWidth: Double? = null,
        pageHeight: Double? = null
    ): TableIdentificationBatchRequest

    /**
     * Parse a batch response into table identifications.
     * Used by batch processing to parse responses with the same logic as interactive mode.
     * 
     * @param responseText JSON response from batch API
     * @param htmlWithIds HTML with injected IDs for CSS selector construction
     * @param metadata Optional metadata from the batch request (contains programmaticTables for merging)
     * @param boundingBoxes Optional element bounding boxes for vision IoU mapping (if useVision=true)
     * @param pageWidth Optional page width for vision mapping
     * @param pageHeight Optional page height for vision mapping
     */
    fun parseBatchResponse(
        responseText: String,
        htmlWithIds: String,
        metadata: Map<String, String>? = null,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>? = null,
        pageWidth: Double? = null,
        pageHeight: Double? = null
    ): List<TableIdentification>
}
