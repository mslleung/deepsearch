package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class GenerateAnswerInput(
    val query: String,
    val markdowns: String
) : IAgent.IAgentInput

data class GenerateAnswerOutput(
    val answer: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IGenerateAnswerAgent : IAgent<GenerateAnswerInput, GenerateAnswerOutput> {
    override suspend fun generate(input: GenerateAnswerInput): GenerateAnswerOutput
}

