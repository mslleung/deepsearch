package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.AnswerStatus
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Input for streaming answer synthesis agent.
 * Provides query and evaluated sources (with extracted facts) to generate an answer from.
 * 
 * @property query The original user query
 * @property evaluatedSources Sources with extracted facts to synthesize the answer from
 * @property imageDescriptions Map of image IDs to their text descriptions, fetched from DB.
 *           The agent uses these descriptions to help the LLM select relevant images.
 * @property previouslySearchedQueries List of queries that have already been searched.
 *           Used to prevent the agent from suggesting duplicate follow-up queries.
 */
data class StreamingAnswerSynthesisInput(
    val query: String,
    val evaluatedSources: List<EvaluatedSource>,
    val imageDescriptions: Map<String, String> = emptyMap(),
    val previouslySearchedQueries: List<String> = emptyList()
) : IAgent.IAgentInput

/**
 * Assessment result for a single dimension of answer quality.
 * Uses semantic satisfied/not-satisfied decision rather than numeric scores.
 * 
 * @property satisfied Whether this dimension is adequately addressed
 * @property rationale Brief explanation for the decision
 * @property followUpQueries Targeted queries to improve this dimension (empty if satisfied)
 */
@Serializable
data class DimensionAssessment(
    val satisfied: Boolean,
    val rationale: String,
    val followUpQueries: List<String> = emptyList()
)

/**
 * 4-dimension quality assessment of the generated answer.
 * All dimensions must be satisfied for the answer to be considered complete.
 * 
 * @property answerCompleteness Whether all parts of the query are addressed
 * @property answerDepth Whether the answer contains specific data vs generic statements
 * @property queryIntentionFulfillment Whether the user would need to search more
 * @property sourceConfidence Whether sources are authoritative and recent
 */
@Serializable
data class AnswerAssessment(
    val answerCompleteness: DimensionAssessment,
    val answerDepth: DimensionAssessment,
    val queryIntentionFulfillment: DimensionAssessment,
    val sourceConfidence: DimensionAssessment
) {
    /**
     * Returns true only if ALL 4 dimensions are satisfied.
     */
    fun isComplete(): Boolean =
        answerCompleteness.satisfied &&
        answerDepth.satisfied &&
        queryIntentionFulfillment.satisfied &&
        sourceConfidence.satisfied

    /**
     * Collects all follow-up queries from unsatisfied dimensions.
     */
    fun allFollowUpQueries(): List<String> = listOf(
        answerCompleteness.followUpQueries,
        answerDepth.followUpQueries,
        queryIntentionFulfillment.followUpQueries,
        sourceConfidence.followUpQueries
    ).flatten()
}

/**
 * Output from streaming answer synthesis agent.
 * Contains the generated comprehensive answer, 4-dimension assessment, and status.
 * 
 * @property answer The synthesized answer text
 * @property citedSourceUrls URLs of sources that were actually cited in the answer
 * @property assessment 4-dimension quality assessment of the answer
 * @property status COMPLETE if all 4 dimensions are satisfied, NEED_MORE_INFORMATION otherwise
 * @property followUpQueries Aggregated follow-up queries from unsatisfied dimensions
 * @property imageIds List of image IDs referenced in the answer
 * @property tokenUsage Token usage metrics for this synthesis call
 */
data class StreamingAnswerSynthesisOutput(
    val answer: String,
    val citedSourceUrls: List<String> = emptyList(),
    val assessment: AnswerAssessment,
    val status: AnswerStatus,
    val followUpQueries: List<String> = emptyList(),
    val imageIds: List<String> = emptyList(),
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
     * @property assessment 4-dimension quality assessment of the answer
     * @property citedSourceUrls URLs of sources that were actually cited in the answer
     * @property status COMPLETE if all 4 dimensions are satisfied, NEED_MORE_INFORMATION otherwise
     * @property followUpQueries Aggregated follow-up queries from unsatisfied dimensions
     * @property imageIds List of image IDs referenced in the answer
     */
    data class Complete(
        val tokenUsage: TokenUsageMetrics,
        val assessment: AnswerAssessment,
        val citedSourceUrls: List<String> = emptyList(),
        val status: AnswerStatus,
        val followUpQueries: List<String> = emptyList(),
        val imageIds: List<String> = emptyList()
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
