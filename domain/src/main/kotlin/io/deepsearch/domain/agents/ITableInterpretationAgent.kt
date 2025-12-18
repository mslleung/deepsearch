package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest

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
    val markdown: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Result of parsing a table interpretation batch response.
 */
data class TableInterpretationBatchResult(
    val markdown: String,
    val additionalInfo: String
)

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
