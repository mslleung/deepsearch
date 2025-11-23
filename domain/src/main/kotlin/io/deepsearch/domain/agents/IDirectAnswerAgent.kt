package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class DirectAnswerInput(
    val screenshotBytes: ByteArray,
    val html: String,
    val searchQuery: SearchQuery
) : IAgent.IAgentInput

data class DirectAnswerOutput(
    val answer: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IDirectAnswerAgent : IAgent<DirectAnswerInput, DirectAnswerOutput> {
    override suspend fun generate(input: DirectAnswerInput): DirectAnswerOutput
}
