package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent

data class TableIdentificationInput(
    val screenshotBytes: ByteArray
) : IAgent.IAgentInput

data class TableIdentificationOutput(
    val tableXPaths: List<String>
) : IAgent.IAgentOutput

interface ITableIdentificationAgent : IAgent<TableIdentificationInput, TableIdentificationOutput> {
    override suspend fun generate(input: TableIdentificationInput): TableIdentificationOutput
}