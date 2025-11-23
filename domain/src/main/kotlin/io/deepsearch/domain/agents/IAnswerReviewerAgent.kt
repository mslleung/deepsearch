package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class AnswerReviewerInput(
    val query: String,
    val currentAnswer: String
) : IAgent.IAgentInput

data class AnswerReviewerOutput(
    val isComplete: Boolean,
    val reason: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IAnswerReviewerAgent : IAgent<AnswerReviewerInput, AnswerReviewerOutput> {
    override suspend fun generate(input: AnswerReviewerInput): AnswerReviewerOutput
}
