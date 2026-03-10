package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.services.ILlmTokenUsageService
import io.deepsearch.domain.agents.AnswerAssessment
import io.deepsearch.domain.agents.IIncrementalSynthesisAgent
import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.IncrementalSynthesisInput
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.agents.StreamingAnswerStreamItem
import io.deepsearch.domain.models.valueobjects.AnswerStatus
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SessionHistory
import kotlinx.coroutines.flow.Flow
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
    /** Whether the answer is complete (FINISH_SEARCH or NOT_FOUND are both terminal) */
    val isComplete: Boolean get() = status == AnswerStatus.FINISH_SEARCH || status == AnswerStatus.NOT_FOUND

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
 * Dispatches to the initial synthesis agent (first call) or incremental agent (subsequent calls)
 * based on whether a current answer state exists.
 */
interface IAnswerSynthesisFacadeService {
    /**
     * Synthesize an answer from evaluated sources.
     * If currentAnswer and currentAssessment are provided, uses the incremental agent
     * (only processes new sources against existing answer). Otherwise uses the initial agent.
     *
     * @param sessionId The session ID for tracking
     * @param expandedQuery The expanded/processed query
     * @param evaluatedSources Sources to synthesize from (all sources for initial, only new for incremental)
     * @param previouslySearchedQueries Queries already searched (for deduplication)
     * @param fulfillmentRequirements Requirements for answer fulfillment
     * @param sessionHistory Full history of prior sessions in the continuation chain
     * @param imageDescriptions Map of image IDs to their text descriptions
     * @param currentAnswer Existing answer to update (null triggers initial synthesis)
     * @param currentCitedSourceUrls URLs already cited in the current answer
     * @param currentAssessment Assessment from the last synthesis (null triggers initial synthesis)
     * @return SynthesisResult containing the answer and metadata
     */
    suspend fun synthesizeAnswer(
        sessionId: QuerySessionId,
        expandedQuery: String,
        evaluatedSources: List<EvaluatedSource>,
        previouslySearchedQueries: List<String> = emptyList(),
        fulfillmentRequirements: List<String> = emptyList(),
        sessionHistory: SessionHistory = SessionHistory.empty(),
        imageDescriptions: Map<String, String> = emptyMap(),
        currentAnswer: String? = null,
        currentCitedSourceUrls: List<String> = emptyList(),
        currentAssessment: AnswerAssessment? = null
    ): SynthesisResult
}

class AnswerSynthesisFacadeService(
    private val initialSynthesisAgent: IStreamingAnswerSynthesisAgent,
    private val incrementalSynthesisAgent: IIncrementalSynthesisAgent,
    private val tokenUsageService: ILlmTokenUsageService
) : IAnswerSynthesisFacadeService {

    override suspend fun synthesizeAnswer(
        sessionId: QuerySessionId,
        expandedQuery: String,
        evaluatedSources: List<EvaluatedSource>,
        previouslySearchedQueries: List<String>,
        fulfillmentRequirements: List<String>,
        sessionHistory: SessionHistory,
        imageDescriptions: Map<String, String>,
        currentAnswer: String?,
        currentCitedSourceUrls: List<String>,
        currentAssessment: AnswerAssessment?
    ): SynthesisResult {
        val streamItems: Flow<StreamingAnswerStreamItem> =
            if (currentAnswer != null && currentAssessment != null) {
                incrementalSynthesisAgent.generateStream(
                    IncrementalSynthesisInput(
                        query = expandedQuery,
                        newSources = evaluatedSources,
                        currentAnswer = currentAnswer,
                        currentCitedSourceUrls = currentCitedSourceUrls,
                        currentAssessment = currentAssessment,
                        imageDescriptions = imageDescriptions,
                        previouslySearchedQueries = previouslySearchedQueries,
                        fulfillmentRequirements = fulfillmentRequirements,
                        sessionHistory = sessionHistory
                    )
                )
            } else {
                initialSynthesisAgent.generateStream(
                    StreamingAnswerSynthesisInput(
                        query = expandedQuery,
                        evaluatedSources = evaluatedSources,
                        imageDescriptions = imageDescriptions,
                        previouslySearchedQueries = previouslySearchedQueries,
                        fulfillmentRequirements = fulfillmentRequirements,
                        sessionHistory = sessionHistory
                    )
                )
            }

        val agentName = if (currentAnswer != null) "IncrementalSynthesisAgent" else "StreamingAnswerSynthesisAgent"

        val answerBuilder = StringBuilder()
        lateinit var status: AnswerStatus
        lateinit var assessment: AnswerAssessment
        var citedSourceUrls = emptyList<String>()
        var followUpQueries = emptyList<String>()
        var refinedRequirements = emptyList<String>()
        var imageIds = emptyList<String>()

        streamItems.collect { item ->
            when (item) {
                is StreamingAnswerStreamItem.Chunk -> answerBuilder.append(item.text)
                is StreamingAnswerStreamItem.Complete -> {
                    tokenUsageService.recordTokenUsage(
                        sessionId, agentName,
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
