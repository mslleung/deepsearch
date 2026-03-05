package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class WebpageReconnaissanceInput(
    val pageText: String,
    val query: String
) : IAgent.IAgentInput

data class WebpageReconnaissanceOutput(
    val pageStructure: String,
    val scrollTargetText: String?,
    val scrollTargetOccurrence: Int,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IWebpageReconnaissanceAgent : IAgent<WebpageReconnaissanceInput, WebpageReconnaissanceOutput> {
    override suspend fun generate(input: WebpageReconnaissanceInput): WebpageReconnaissanceOutput
}
