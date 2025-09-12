package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType

data class IconInterpretationInput(
    val bytes: ByteArray,
    val mimeType: ImageMimeType,
    val context: Map<String, String> = emptyMap()
) : IAgent.IAgentInput

data class IconInterpretationOutput(
    val label: String,
    val confidence: Double,
    val hints: List<String> = emptyList()
) : IAgent.IAgentOutput

interface IIconInterpreterAgent : IAgent<IconInterpretationInput, IconInterpretationOutput> {
    override suspend fun generate(input: IconInterpretationInput): IconInterpretationOutput
}