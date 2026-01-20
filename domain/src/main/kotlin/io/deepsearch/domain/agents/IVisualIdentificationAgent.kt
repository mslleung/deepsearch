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
 * The browser can be released before visual identification begins.
 */
data class VisualIdentificationInput(
    /** Pre-captured page snapshot containing HTML, bounding boxes, and hidden containers. */
    val pageSnapshot: IBrowserPage.PageSnapshotWithMetadata,
    /** Full-page screenshot for vision-based detection (required). */
    val screenshot: IBrowserPage.Screenshot
) : IAgent.IAgentInput

/**
 * Identification of a navigation menu found in hidden containers (mobile menus, etc.).
 * These are typically duplicate navigation structures that should be removed from the output.
 */
/**
 * Represents a hidden mobile layout structure detected in a hidden container.
 * Mobile layouts are duplicate UI structures that should be removed from extraction output.
 */
@Serializable
data class MobileLayoutIdentification(
    /** Stable data-ds-id value for removal (e.g., "ds-element-456") */
    val dataId: String,
    /** Description of the mobile layout (e.g., "Mobile navigation menu", "Hamburger menu content") */
    val description: String
)

/**
 * Combined output containing both semantic elements and table identifications.
 * This is the result of a single vision-based LLM call that detects both types.
 */
@Serializable
data class VisualIdentificationOutput(
    /** Semantic/navigation elements identified on the page */
    val semanticElements: SemanticElements,
    /** Tables/grids identified on the page (includes both vision-detected and hidden container tables) */
    val tables: List<TableIdentification>,
    /** Mobile layouts found in hidden containers (duplicate UI structures to be removed) */
    val hiddenMobileLayouts: List<MobileLayoutIdentification> = emptyList(),
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
 * Combined visual identification agent that detects both semantic elements and tables
 * in a single vision-based LLM call.
 * 
 * This agent merges the functionality of ISemanticIdentificationAgent and 
 * ITableIdentificationAgent for vision-based detection, reducing:
 * - LLM calls from 2 to 1
 * - Total latency (single call instead of parallel calls with overhead)
 * - Token usage (image tokens sent only once)
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
