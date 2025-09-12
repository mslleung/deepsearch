package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.serialization.Serializable

data class TableIdentificationInput(
    val screenshotBytes: ByteArray,
    val mimetype: ImageMimeType
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
