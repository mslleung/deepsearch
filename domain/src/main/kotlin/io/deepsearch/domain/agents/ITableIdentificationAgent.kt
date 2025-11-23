package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Serializable

data class TableIdentificationInput(
    val webpage: IBrowserPage
) : IAgent.IAgentInput

@Serializable
data class TableIdentification(
    val cssSelector: String,
    val auxiliaryInfo: String
)

data class TableIdentificationOutput(
    val tables: List<TableIdentification>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface ITableIdentificationAgent : IAgent<TableIdentificationInput, TableIdentificationOutput> {
    override suspend fun generate(input: TableIdentificationInput): TableIdentificationOutput
}
