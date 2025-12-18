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
 * Only requires the pre-captured page snapshot - no live browser needed.
 * The browser can be released before semantic identification begins.
 */
data class SemanticIdentificationInput(
    /** Pre-captured page snapshot containing HTML and bounding boxes (without media). */
    val pageSnapshot: IBrowserPage.PageSnapshotWithMetadata
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
     */
    fun prepareBatchRequest(requestId: String, html: String): SemanticIdentificationBatchRequest

    /**
     * Parse a batch response into semantic elements.
     * Used by batch processing to parse responses with the same logic as interactive mode.
     */
    fun parseBatchResponse(responseText: String, htmlWithIds: String): SemanticElements
}

