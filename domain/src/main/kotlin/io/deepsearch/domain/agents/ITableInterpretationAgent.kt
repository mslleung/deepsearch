package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

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

interface ITableInterpretationAgent : IAgent<TableInterpretationInput, TableInterpretationOutput> {
    override suspend fun generate(input: TableInterpretationInput): TableInterpretationOutput
}
