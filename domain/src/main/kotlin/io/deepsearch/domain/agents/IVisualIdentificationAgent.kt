package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Input for combined visual identification (semantic elements + tables).
 * 
 * Uses vision-based detection for both semantic elements (headers, nav, footer, etc.)
 * and tables/grids in a single LLM call, reducing latency and token usage.
 * 
 * Note: This only handles visible content. Hidden container analysis is done separately
 * using TableGridDetector with bounding box data from captureHiddenContainerBoundingBoxes().
 * 
 * The browser can be released before visual identification begins.
 */
data class VisualIdentificationInput(
    /** Pre-captured page snapshot containing HTML and bounding boxes. */
    val pageSnapshot: IBrowserPage.PageSnapshotWithMetadata,
    /** Full-page screenshot for vision-based detection (required). */
    val screenshot: IBrowserPage.Screenshot
) : IAgent.IAgentInput

/**
 * Combined output containing both semantic elements and table identifications.
 * This is the result of a single vision-based LLM call that detects visible content only.
 * 
 * Hidden container tables are detected separately using TableGridDetector and merged
 * in the application layer.
 */
@Serializable
data class VisualIdentificationOutput(
    /** Semantic/navigation elements identified on the page */
    val semanticElements: SemanticElements,
    /** Tables/grids identified on the visible page (vision-detected and programmatic <table> elements) */
    val tables: List<TableIdentification>,
    /** Token usage for the combined call */
    @Contextual val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Prepared batch request for visual identification.
 * Contains the request to submit and the HTML with injected IDs for response parsing.
 */
data class VisualIdentificationBatchRequest(
    val request: BatchContentRequest,
    val htmlWithIds: String
)

/**
 * Combined visual identification agent that detects both semantic elements and CSS/div-based tables
 * in a single vision-based LLM call.
 * 
 * This agent handles vision-based detection of:
 * - Semantic elements: header, footer, nav sidebar, breadcrumb, cookie banner, popups
 * - CSS/div-based tables: grid layouts using CSS flexbox/grid, not semantic HTML `<table>`
 * 
 * Note: Semantic HTML `<table>` elements are extracted separately via static analysis
 * in WebpageExtractionService, not through this vision-based agent.
 */
interface IVisualIdentificationAgent : IAgent<VisualIdentificationInput, VisualIdentificationOutput> {
    override suspend fun generate(input: VisualIdentificationInput): VisualIdentificationOutput

    /**
     * Prepare a batch request for visual identification.
     * Used by batch processing to create requests with the same prompts as interactive mode.
     * 
     * @param requestId Unique request ID for batch tracking
     * @param html Raw HTML content
     * @param screenshotBase64 Base64-encoded screenshot for vision-based detection (required)
     * @param screenshotMimeType MIME type for screenshot (e.g., "image/png")
     * @param boundingBoxes Element bounding boxes for vision IoU mapping
     * @param pageWidth Page width in pixels for vision mapping
     * @param pageHeight Page height in pixels for vision mapping
     */
    fun prepareBatchRequest(
        requestId: String,
        html: String,
        screenshotBase64: String,
        screenshotMimeType: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        pageWidth: Double,
        pageHeight: Double
    ): VisualIdentificationBatchRequest

    /**
     * Parse a batch response into visual identification output.
     * Used by batch processing to parse responses with the same logic as interactive mode.
     * 
     * @param responseText JSON response from batch API
     * @param htmlWithIds HTML with injected IDs for CSS selector construction
     * @param boundingBoxes Element bounding boxes for vision IoU mapping
     * @param pageWidth Page width for vision mapping
     * @param pageHeight Page height for vision mapping
     */
    fun parseBatchResponse(
        responseText: String,
        htmlWithIds: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        pageWidth: Double,
        pageHeight: Double
    ): VisualIdentificationOutput
}
