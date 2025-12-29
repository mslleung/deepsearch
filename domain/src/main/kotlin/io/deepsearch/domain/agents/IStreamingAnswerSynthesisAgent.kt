package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.AnswerType
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.flow.Flow

/**
 * Input for streaming answer synthesis agent.
 * Provides query and shortlisted sources (with extracted facts) to generate an answer from.
 * 
 * @property query The original user query
 * @property shortlistedSources Sources with extracted facts to synthesize the answer from
 * @property expandedQuery Optional clarified/expanded version of the query that captures the core intent.
 *           If provided, this is used for answer synthesis instead of the original query.
 *           For example: "tell me about the pricing" → "What are the main subscription plans and pricing tiers?"
 */
data class StreamingAnswerSynthesisInput(
    val query: String,
    val shortlistedSources: List<ShortlistedSource>,
    val expandedQuery: String? = null
) : IAgent.IAgentInput {
    /**
     * Returns the effective query to use for answer synthesis.
     * Prefers expandedQuery if available, otherwise falls back to the original query.
     */
    val effectiveQuery: String get() = expandedQuery ?: query
}

/**
 * Output from streaming answer synthesis agent.
 * Contains the generated comprehensive answer, answer type classification, reasoning, and referenced image IDs.
 */
data class StreamingAnswerSynthesisOutput(
    val answer: String,
    val answerType: AnswerType,
    val reasoning: String,
    val imageIds: List<String> = emptyList(),
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput {
    /**
     * Whether a meaningful answer was found (DIRECT_ANSWER or INFERRED_ANSWER).
     * PARTIAL_MENTION is not considered a found answer.
     */
    val answerFound: Boolean get() = answerType != AnswerType.PARTIAL_MENTION
}

/**
 * Items emitted during streaming answer generation.
 */
sealed class StreamingAnswerStreamItem {
    /**
     * A chunk of answer text.
     */
    data class Chunk(val text: String) : StreamingAnswerStreamItem()

    /**
     * Emitted after all chunks, contains token usage, answer type, reasoning, and referenced image IDs.
     */
    data class Complete(
        val tokenUsage: TokenUsageMetrics,
        val answerType: AnswerType,
        val reasoning: String,
        val imageIds: List<String> = emptyList()
    ) : StreamingAnswerStreamItem() {
        /**
         * Whether a meaningful answer was found (DIRECT_ANSWER or INFERRED_ANSWER).
         * PARTIAL_MENTION is not considered a found answer.
         */
        val answerFound: Boolean get() = answerType != AnswerType.PARTIAL_MENTION
    }
}

/**
 * Agent that generates a comprehensive answer from shortlisted sources with extracted facts.
 * 
 * This agent receives facts (not full markdown content) from the shortlist agent
 * and synthesizes them into a comprehensive answer.
 * 
 * Supports streaming answer generation for real-time output.
 */
interface IStreamingAnswerSynthesisAgent : IAgent<StreamingAnswerSynthesisInput, StreamingAnswerSynthesisOutput> {
    override suspend fun generate(input: StreamingAnswerSynthesisInput): StreamingAnswerSynthesisOutput

    /**
     * Stream answer generation, emitting answer text chunks as they are generated.
     * Uses structured JSON output internally but extracts answer deltas for streaming.
     * The last emission is a StreamingAnswerStreamItem.Complete containing token usage metadata.
     *
     * @param input The query and shortlisted sources to generate an answer from
     * @return Flow of StreamingAnswerStreamItem (Chunk for text, Complete for final token usage)
     */
    fun generateStream(input: StreamingAnswerSynthesisInput): Flow<StreamingAnswerStreamItem>
}

