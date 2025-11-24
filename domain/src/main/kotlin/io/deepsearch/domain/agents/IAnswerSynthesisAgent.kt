package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

/**
 * Input for answer synthesis agent.
 * Provides query and shortlisted sources to generate a comprehensive answer from.
 */
data class AnswerSynthesisInput(
    val query: String,
    val shortlistedSources: List<ShortlistedSource>
) : IAgent.IAgentInput

/**
 * Output from answer synthesis agent.
 * Contains the generated comprehensive answer.
 */
data class AnswerSynthesisOutput(
    val answer: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that generates a comprehensive answer from shortlisted sources.
 * Focuses solely on building a high-quality answer from pre-curated sources.
 */
interface IAnswerSynthesisAgent : IAgent<AnswerSynthesisInput, AnswerSynthesisOutput> {
    override suspend fun generate(input: AnswerSynthesisInput): AnswerSynthesisOutput
}

