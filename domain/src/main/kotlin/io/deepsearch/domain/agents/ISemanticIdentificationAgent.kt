package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Input for semantic element identification.
 * 
 * Uses vision-based detection for visible semantic elements (headers, nav, footer, etc.).
 * The browser can be released before semantic identification begins.
 */
data class SemanticIdentificationInput(
    /** Pre-captured page snapshot containing HTML and bounding boxes. */
    val pageSnapshot: IBrowserPage.PageSnapshotWithMetadata,
    /** Full-page screenshot for vision-based detection (required). */
    val screenshot: IBrowserPage.Screenshot
) : IAgent.IAgentInput

@Serializable
data class SemanticIdentificationOutput(
    val elements: SemanticElements,
    @Contextual val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Prepared batch request for semantic identification.
 * Contains the request to submit and the HTML with injected IDs for response parsing.
 */
data class SemanticIdentificationBatchRequest(
    val request: BatchContentRequest,
    val htmlWithIds: String
)

interface ISemanticIdentificationAgent : IAgent<SemanticIdentificationInput, SemanticIdentificationOutput> {
    override suspend fun generate(input: SemanticIdentificationInput): SemanticIdentificationOutput

    /**
     * Prepare a batch request for semantic identification.
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
    ): SemanticIdentificationBatchRequest

    /**
     * Parse a batch response into semantic elements.
     * Used by batch processing to parse responses with the same logic as interactive mode.
     * 
     * @param responseText JSON response from batch API
     * @param htmlWithIds HTML with injected IDs for CSS selector construction
     * @param boundingBoxes Optional element bounding boxes for vision IoU mapping
     * @param pageWidth Optional page width for vision mapping
     * @param pageHeight Optional page height for vision mapping
     */
    fun parseBatchResponse(
        responseText: String,
        htmlWithIds: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>? = null,
        pageWidth: Double? = null,
        pageHeight: Double? = null
    ): SemanticElements
}

