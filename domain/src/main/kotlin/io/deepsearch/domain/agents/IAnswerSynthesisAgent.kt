package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.flow.Flow

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
 * Items emitted during streaming answer generation.
 */
sealed class AnswerStreamItem {
    /**
     * A chunk of answer text.
     */
    data class Chunk(val text: String) : AnswerStreamItem()

    /**
     * Emitted after all chunks, contains token usage from the final response chunk.
     */
    data class Complete(val tokenUsage: TokenUsageMetrics) : AnswerStreamItem()
}

/**
 * Agent that generates a comprehensive answer from shortlisted sources.
 * Focuses solely on building a high-quality answer from pre-curated sources.
 */
interface IAnswerSynthesisAgent : IAgent<AnswerSynthesisInput, AnswerSynthesisOutput> {
    override suspend fun generate(input: AnswerSynthesisInput): AnswerSynthesisOutput

    /**
     * Stream answer generation, emitting answer text chunks as they are generated.
     * Uses structured JSON output internally but extracts answer deltas for streaming.
     * The last emission is an AnswerStreamItem.Complete containing token usage metadata.
     *
     * @param input The query and shortlisted sources to generate an answer from
     * @return Flow of AnswerStreamItem (Chunk for text, Complete for final token usage)
     */
    fun generateStream(input: AnswerSynthesisInput): Flow<AnswerStreamItem>
}

