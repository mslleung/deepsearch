package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.AnswerStatus
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.SessionHistory
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Input for streaming answer synthesis agent.
 * Provides query and evaluated sources (with extracted facts) to generate an answer from.
 * 
 * @property query The expanded/context-aware query to answer
 * @property evaluatedSources Sources with extracted facts to synthesize the answer from
 * @property imageDescriptions Map of image IDs to their text descriptions, fetched from DB.
 *           The agent uses these descriptions to help the LLM select relevant images.
 * @property previouslySearchedQueries List of queries that have already been searched.
 *           Used to prevent the agent from suggesting duplicate follow-up queries.
 * @property fulfillmentRequirements List of requirements that must ALL be satisfied
 *           for the answer to be considered complete. Used for COVERAGE evaluation.
 * @property sessionHistory Full history of prior sessions in the continuation chain.
 *           When provided, the agent will build upon prior findings without repeating them.
 *           Contains all prior queries and answers in chronological order.
 */
data class StreamingAnswerSynthesisInput(
    val query: String,
    val evaluatedSources: List<EvaluatedSource>,
    val imageDescriptions: Map<String, String> = emptyMap(),
    val previouslySearchedQueries: List<String> = emptyList(),
    val fulfillmentRequirements: List<String> = emptyList(),
    val sessionHistory: SessionHistory = SessionHistory.empty()
) : IAgent.IAgentInput

/**
 * Assessment result for a single dimension of answer quality.
 * Uses semantic satisfied/not-satisfied decision rather than numeric scores.
 * 
 * @property satisfied Whether this dimension is adequately addressed
 * @property rationale Brief explanation for the decision
 */
@Serializable
data class DimensionAssessment(
    val satisfied: Boolean,
    val rationale: String
)

/**
 * 5-dimension assessment of whether the source batch is sufficient for answering.
 * All dimensions must be satisfied for the answer to be considered complete.
 * 
 * @property coverage Whether facts address all parts of the query; multiple sources for negative conclusions
 * @property depth Whether facts contain specific data (numbers, prices, dates) vs vague statements
 * @property temporality Whether sources are recent enough for time-sensitive queries
 * @property authority Whether sources are official/authoritative vs third-party/user-generated
 * @property consistency Whether facts from different sources agree vs conflict
 */
@Serializable
data class AnswerAssessment(
    val coverage: DimensionAssessment,
    val depth: DimensionAssessment,
    val temporality: DimensionAssessment,
    val authority: DimensionAssessment,
    val consistency: DimensionAssessment
) {
    /**
     * Returns true only if ALL 5 dimensions are satisfied.
     */
    fun isComplete(): Boolean =
        coverage.satisfied &&
        depth.satisfied &&
        temporality.satisfied &&
        authority.satisfied &&
        consistency.satisfied
}

/**
 * Output from streaming answer synthesis agent.
 * Contains the generated comprehensive answer, 5-dimension batch assessment, and continuation status.
 * 
 * @property answer The synthesized answer text
 * @property citedSourceUrls URLs of sources that were actually cited in the answer
 * @property assessment 5-dimension source batch assessment
 * @property status FINISH_SEARCH if all 5 dimensions are satisfied, CONTINUE_SEARCH otherwise
 * @property followUpQueries Suggested queries to gather more information (independent of assessment)
 * @property refinedRequirements Updated fulfillment requirements based on discovered information.
 *           When CONTINUE_SEARCH, may contain additions/splits/removals based on what was learned.
 *           Empty list means no refinement (keep current requirements).
 * @property imageIds List of image IDs referenced in the answer
 * @property tokenUsage Token usage metrics for this synthesis call
 */
data class StreamingAnswerSynthesisOutput(
    val answer: String,
    val citedSourceUrls: List<String> = emptyList(),
    val assessment: AnswerAssessment,
    val status: AnswerStatus,
    val followUpQueries: List<String> = emptyList(),
    val refinedRequirements: List<String> = emptyList(),
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
     * Emitted after all chunks, contains continuation status, token usage, and feedback loop data.
     * 
     * @property tokenUsage Token usage metrics for this synthesis call
     * @property assessment 5-dimension source batch assessment
     * @property citedSourceUrls URLs of sources that were actually cited in the answer
     * @property status FINISH_SEARCH if all 5 dimensions are satisfied, CONTINUE_SEARCH otherwise
     * @property followUpQueries Suggested queries to gather more information (independent of assessment)
     * @property refinedRequirements Updated fulfillment requirements based on discovered information
     * @property imageIds List of image IDs referenced in the answer
     */
    data class Complete(
        val tokenUsage: TokenUsageMetrics,
        val assessment: AnswerAssessment,
        val citedSourceUrls: List<String> = emptyList(),
        val status: AnswerStatus,
        val followUpQueries: List<String> = emptyList(),
        val refinedRequirements: List<String> = emptyList(),
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
