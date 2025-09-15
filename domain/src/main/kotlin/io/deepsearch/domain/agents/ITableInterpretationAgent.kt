package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType

data class TableInterpretationInput(
    val screenshotBytes: ByteArray,
    val mimetype: ImageMimeType,
    val html: String
) : IAgent.IAgentInput

data class TableInterpretationOutput(
    val markdown: String
) : IAgent.IAgentOutput

interface ITableInterpretationAgent : IAgent<TableInterpretationInput, TableInterpretationOutput> {
    override suspend fun generate(input: TableInterpretationInput): TableInterpretationOutput
}


