package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import kotlinx.serialization.Serializable

data class TableIdentificationInput(
    val html: String
) : IAgent.IAgentInput

@Serializable
data class TableIdentification(
    val xpath: String,
    val auxiliaryInfo: String
)

data class TableIdentificationOutput(
    val tables: List<TableIdentification>
) : IAgent.IAgentOutput

interface ITableIdentificationAgent : IAgent<TableIdentificationInput, TableIdentificationOutput> {
    override suspend fun generate(input: TableIdentificationInput): TableIdentificationOutput
}
