package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.SessionHistory
import kotlinx.coroutines.flow.Flow

/**
 * Input for the incremental synthesis agent.
 * Provides only newly discovered sources alongside the current answer state,
 * allowing the agent to decide whether/how to update the existing answer.
 *
 * @property query The expanded/context-aware query to answer
 * @property newSources Only the sources discovered since the last synthesis call
 * @property currentAnswer The existing synthesized answer to potentially update
 * @property currentCitedSourceUrls URLs already cited in the current answer
 * @property currentAssessment The 5-dimension assessment from the last synthesis
 * @property imageDescriptions Map of image IDs to their text descriptions
 * @property previouslySearchedQueries Queries already searched (for deduplication)
 * @property fulfillmentRequirements Requirements that must be satisfied for completeness
 * @property sessionHistory Full history of prior sessions in the continuation chain
 */
data class IncrementalSynthesisInput(
    val query: String,
    val newSources: List<EvaluatedSource>,
    val currentAnswer: String,
    val currentCitedSourceUrls: List<String>,
    val currentAssessment: AnswerAssessment,
    val imageDescriptions: Map<String, String> = emptyMap(),
    val previouslySearchedQueries: List<String> = emptyList(),
    val fulfillmentRequirements: List<String> = emptyList(),
    val sessionHistory: SessionHistory = SessionHistory.empty()
) : IAgent.IAgentInput

/**
 * Agent that incrementally updates an existing answer with newly discovered sources.
 *
 * Unlike the initial synthesis agent (which generates from scratch), this agent
 * evaluates new sources against the current answer and decides whether they
 * improve it. This avoids re-processing all accumulated sources on every call.
 *
 * Shares the same output types as the initial synthesis agent.
 */
interface IIncrementalSynthesisAgent : IAgent<IncrementalSynthesisInput, StreamingAnswerSynthesisOutput> {
    override suspend fun generate(input: IncrementalSynthesisInput): StreamingAnswerSynthesisOutput

    /**
     * Stream incremental answer generation, emitting answer text chunks as they are generated.
     * The last emission is a StreamingAnswerStreamItem.Complete containing token usage metadata.
     */
    fun generateStream(input: IncrementalSynthesisInput): Flow<StreamingAnswerStreamItem>
}
