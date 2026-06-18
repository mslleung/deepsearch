package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class VisualContentExtractionInput(
    val regionImage: ByteArray,
    val imageMimeType: ImageMimeType,
    val query: String,
    val regionDescription: String
) : IAgent.IAgentInput

data class VisualContentExtractionOutput(
    val markdown: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IVisualContentExtractionAgent : IAgent<VisualContentExtractionInput, VisualContentExtractionOutput> {
    override suspend fun generate(input: VisualContentExtractionInput): VisualContentExtractionOutput
}
