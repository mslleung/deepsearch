package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.AnswerType
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.flow.Flow

/**
 * Input for streaming answer synthesis agent.
 * Provides query and evaluated sources (with extracted facts) to generate an answer from.
 * 
 * @property query The original user query
 * @property evaluatedSources Sources with extracted facts to synthesize the answer from
 * @property expandedQuery Optional clarified/expanded version of the query that captures the core intent.
 *           If provided, this is used for answer synthesis instead of the original query.
 *           For example: "tell me about the pricing" → "What are the main subscription plans and pricing tiers?"
 */
data class StreamingAnswerSynthesisInput(
    val query: String,
    val evaluatedSources: List<EvaluatedSource>,
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
    val tokenUsage: TokenUsageMetrics,
    val citedSourceUrls: List<String> = emptyList()
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
     * 
     * @property tokenUsage Token usage metrics for this synthesis call
     * @property answerType Classification of how well the answer addresses the query.
     *           Use this to determine if answer is acceptable:
     *           - DIRECT_ANSWER: Confident answer (acceptable for both preview and main paths)
     *           - INFERRED_ANSWER: Reasonable answer (acceptable for main path only)
     *           - PARTIAL_MENTION: Incomplete (continue collecting sources)
     * @property reasoning Explanation of how the answer was derived
     * @property imageIds List of image IDs referenced in the answer
     * @property citedSourceUrls URLs of sources that were actually cited in the answer.
     *           Used to filter the final source list to only include sources that contributed to the answer.
     */
    data class Complete(
        val tokenUsage: TokenUsageMetrics,
        val answerType: AnswerType,
        val reasoning: String,
        val imageIds: List<String> = emptyList(),
        val citedSourceUrls: List<String> = emptyList()
    ) : StreamingAnswerStreamItem() {
        /**
         * Whether a meaningful answer was found (DIRECT_ANSWER or INFERRED_ANSWER).
         * PARTIAL_MENTION is not considered a found answer.
         */
        val answerFound: Boolean get() = answerType != AnswerType.PARTIAL_MENTION
    }
}

/**
 * Agent that generates a comprehensive answer from evaluated sources with extracted facts.
 * 
 * This agent receives facts (not full markdown content) from the source eval agents
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
     * @param input The query and evaluated sources to generate an answer from
     * @return Flow of StreamingAnswerStreamItem (Chunk for text, Complete for final token usage)
     */
    fun generateStream(input: StreamingAnswerSynthesisInput): Flow<StreamingAnswerStreamItem>
}
