package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent

data class AnswerReviewerInput(
    val query: String,
    val currentAnswer: String
) : IAgent.IAgentInput

data class AnswerReviewerOutput(
    val isComplete: Boolean,
    val reason: String
) : IAgent.IAgentOutput

interface IAnswerReviewerAgent : IAgent<AnswerReviewerInput, AnswerReviewerOutput> {
    override suspend fun generate(input: AnswerReviewerInput): AnswerReviewerOutput
}
