package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class TableInterpretationInput(
    val tableIdentification: TableIdentification,
    val webpage: IBrowserPage
) : IAgent.IAgentInput

data class TableInterpretationOutput(
    val markdown: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface ITableInterpretationAgent : IAgent<TableInterpretationInput, TableInterpretationOutput> {
    override suspend fun generate(input: TableInterpretationInput): TableInterpretationOutput
}
