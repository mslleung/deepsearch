package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType

data class IconInterpreterInput(
    val bytes: ByteArray,
    val mimeType: ImageMimeType,
    val context: Map<String, String> = emptyMap()
) : IAgent.IAgentInput

data class IconInterpreterOutput(
    val label: String
) : IAgent.IAgentOutput

interface IIconInterpreterAgent : IAgent<IconInterpreterInput, IconInterpreterOutput> {
    override suspend fun generate(input: IconInterpreterInput): IconInterpreterOutput
}