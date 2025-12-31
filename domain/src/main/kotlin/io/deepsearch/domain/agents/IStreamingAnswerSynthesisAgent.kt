package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.AnswerStatus
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
 * @property previouslySearchedQueries List of queries that have already been searched.
 *           Used to prevent the agent from suggesting duplicate follow-up queries.
 * @property targetDomain The target domain for the search (used for site: prefixing follow-up queries)
 */
data class StreamingAnswerSynthesisInput(
    val query: String,
    val evaluatedSources: List<EvaluatedSource>,
    val previouslySearchedQueries: List<String> = emptyList(),
    val targetDomain: String = ""
) : IAgent.IAgentInput

/**
 * Output from streaming answer synthesis agent.
 * Contains the generated comprehensive answer, status, and optional follow-up queries for the feedback loop.
 * 
 * @property answer The synthesized answer text
 * @property status COMPLETE if the answer is sufficient, NEEDS_MORE_SOURCES if more searching is needed
 * @property answerType Quality classification (DIRECT_ANSWER, INFERRED_ANSWER, PARTIAL_MENTION) - for logging only
 * @property reasoning Explanation of how the answer was derived and why status was chosen
 * @property followUpQueries Targeted search queries to find missing information (required when status=NEEDS_MORE_SOURCES)
 * @property whatsMissing Description of what information is missing (required when status=NEEDS_MORE_SOURCES)
 * @property imageIds List of image IDs referenced in the answer
 * @property citedSourceUrls URLs of sources that were actually cited in the answer
 * @property tokenUsage Token usage metrics for this synthesis call
 */
data class StreamingAnswerSynthesisOutput(
    val answer: String,
    val status: AnswerStatus,
    val answerType: AnswerType,
    val reasoning: String,
    val followUpQueries: List<String> = emptyList(),
    val whatsMissing: String? = null,
    val imageIds: List<String> = emptyList(),
    val citedSourceUrls: List<String> = emptyList(),
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Items emitted during streaming answer generation.
 */
sealed class StreamingAnswerStreamItem {
    /**
     * A chunk of answer text.
     */
    data class Chunk(val text: String) : StreamingAnswerStreamItem()

    /**
     * Emitted after all chunks, contains status, token usage, and feedback loop data.
     * 
     * @property tokenUsage Token usage metrics for this synthesis call
     * @property status COMPLETE if the answer is sufficient, NEEDS_MORE_SOURCES if more searching is needed
     * @property answerType Quality classification (DIRECT_ANSWER, INFERRED_ANSWER, PARTIAL_MENTION) - for logging only
     * @property reasoning Explanation of how the answer was derived and why status was chosen
     * @property followUpQueries Targeted search queries to find missing information (when status=NEEDS_MORE_SOURCES)
     * @property whatsMissing Description of what information is missing (when status=NEEDS_MORE_SOURCES)
     * @property imageIds List of image IDs referenced in the answer
     * @property citedSourceUrls URLs of sources that were actually cited in the answer
     */
    data class Complete(
        val tokenUsage: TokenUsageMetrics,
        val status: AnswerStatus,
        val answerType: AnswerType,
        val reasoning: String,
        val followUpQueries: List<String> = emptyList(),
        val whatsMissing: String? = null,
        val imageIds: List<String> = emptyList(),
        val citedSourceUrls: List<String> = emptyList()
    ) : StreamingAnswerStreamItem()
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
