package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class IconInterpreterInput(
    val bytes: ByteArray,
    val mimeType: ImageMimeType
) : IAgent.IAgentInput

data class IconInterpreterOutput(
    val label: String?,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IIconInterpreterAgent : IAgent<IconInterpreterInput, IconInterpreterOutput> {
    override suspend fun generate(input: IconInterpreterInput): IconInterpreterOutput
}