package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.services.ILlmTokenUsageService
import io.deepsearch.domain.agents.AnswerAssessment
import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.agents.StreamingAnswerStreamItem
import io.deepsearch.domain.models.valueobjects.AnswerStatus
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SessionHistory
import kotlinx.coroutines.flow.collect

/**
 * Result from answer synthesis, containing all relevant data from the streaming response.
 */
data class SynthesisResult(
    val answer: String,
    val citedSourceUrls: List<String>,
    val assessment: AnswerAssessment,
    val status: AnswerStatus,
    val followUpQueries: List<String>,
    val refinedRequirements: List<String>,
    val imageIds: List<String>
) {
    /** Whether the answer is complete (status=FINISH_SEARCH from synthesis agent) */
    val isComplete: Boolean get() = status == AnswerStatus.FINISH_SEARCH

    /** Returns a brief summary of unsatisfied dimensions for logging */
    fun getUnsatisfiedSummary(): String {
        val unsatisfied = mutableListOf<String>()
        if (!assessment.coverage.satisfied) unsatisfied.add("coverage")
        if (!assessment.depth.satisfied) unsatisfied.add("depth")
        if (!assessment.temporality.satisfied) unsatisfied.add("temporality")
        if (!assessment.authority.satisfied) unsatisfied.add("authority")
        if (!assessment.consistency.satisfied) unsatisfied.add("consistency")
        return if (unsatisfied.isEmpty()) "all satisfied" else unsatisfied.joinToString(", ")
    }
}

/**
 * Facade service for synthesizing answers from evaluated sources.
 * Consolidates answer generation logic into a single service.
 */
interface IAnswerSynthesisFacadeService {
    /**
     * Synthesize an answer from evaluated sources.
     * Handles streaming collection and token usage recording.
     * Supports the feedback loop with previouslySearchedQueries.
     * 
     * @param sessionId The session ID for tracking
     * @param expandedQuery The expanded/processed query
     * @param evaluatedSources List of evaluated sources to synthesize from
     * @param previouslySearchedQueries Queries already searched (for deduplication)
     * @param fulfillmentRequirements Requirements for answer fulfillment
     * @param sessionHistory Full history of prior sessions in the continuation chain.
     *                       When provided, the answer synthesis will avoid repeating
     *                       information already provided and will build upon prior context.
     * @return SynthesisResult containing the answer and metadata
     */
    suspend fun synthesizeAnswer(
        sessionId: QuerySessionId,
        expandedQuery: String,
        evaluatedSources: List<EvaluatedSource>,
        previouslySearchedQueries: List<String> = emptyList(),
        fulfillmentRequirements: List<String> = emptyList(),
        sessionHistory: SessionHistory = SessionHistory.empty()
    ): SynthesisResult
}

class AnswerSynthesisFacadeService(
    private val streamingAnswerSynthesisAgent: IStreamingAnswerSynthesisAgent,
    private val tokenUsageService: ILlmTokenUsageService
) : IAnswerSynthesisFacadeService {

    override suspend fun synthesizeAnswer(
        sessionId: QuerySessionId,
        expandedQuery: String,
        evaluatedSources: List<EvaluatedSource>,
        previouslySearchedQueries: List<String>,
        fulfillmentRequirements: List<String>,
        sessionHistory: SessionHistory
    ): SynthesisResult {
        val answerBuilder = StringBuilder()
        lateinit var status: AnswerStatus
        lateinit var assessment: AnswerAssessment
        var citedSourceUrls = emptyList<String>()
        var followUpQueries = emptyList<String>()
        var refinedRequirements = emptyList<String>()
        var imageIds = emptyList<String>()

        streamingAnswerSynthesisAgent.generateStream(
            StreamingAnswerSynthesisInput(
                query = expandedQuery,
                evaluatedSources = evaluatedSources,
                previouslySearchedQueries = previouslySearchedQueries,
                fulfillmentRequirements = fulfillmentRequirements,
                sessionHistory = sessionHistory
            )
        ).collect { item ->
            when (item) {
                is StreamingAnswerStreamItem.Chunk -> answerBuilder.append(item.text)
                is StreamingAnswerStreamItem.Complete -> {
                    tokenUsageService.recordTokenUsage(
                        sessionId, "StreamingAnswerSynthesisAgent",
                        item.tokenUsage.modelName, item.tokenUsage.promptTokens,
                        item.tokenUsage.outputTokens, item.tokenUsage.totalTokens
                    )
                    assessment = item.assessment
                    citedSourceUrls = item.citedSourceUrls
                    status = item.status
                    followUpQueries = item.followUpQueries
                    refinedRequirements = item.refinedRequirements
                    imageIds = item.imageIds
                }
            }
        }

        return SynthesisResult(
            answer = answerBuilder.toString(),
            citedSourceUrls = citedSourceUrls,
            assessment = assessment,
            status = status,
            followUpQueries = followUpQueries,
            refinedRequirements = refinedRequirements,
            imageIds = imageIds
        )
    }
}
