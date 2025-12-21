package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.PreviewShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.flow.Flow

/**
 * Input for preview answer synthesis agent.
 * Provides query and shortlisted sources from the preview path.
 */
data class PreviewAnswerSynthesisInput(
    val query: String,
    val shortlistedSources: List<PreviewShortlistedSource>
) : IAgent.IAgentInput

/**
 * Output from preview answer synthesis agent.
 * Contains the generated answer with confidence metrics.
 */
data class PreviewAnswerSynthesisOutput(
    val answer: String,
    val answerFound: Boolean,
    val confidence: Float,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that generates answers from preview shortlisted sources.
 * 
 * This agent is CONSERVATIVE - it:
 * - Only cites absolute facts from shortlisted sources
 * - Prefers "I cannot confidently answer" over guessing
 * - Does not speculate or infer from ambiguous data
 * 
 * The preview answer is meant as an early exit for simple static content.
 */
interface IPreviewAnswerSynthesisAgent : IAgent<PreviewAnswerSynthesisInput, PreviewAnswerSynthesisOutput> {
    override suspend fun generate(input: PreviewAnswerSynthesisInput): PreviewAnswerSynthesisOutput

    /**
     * Stream answer generation, emitting answer text chunks as they are generated.
     * The last emission is an AnswerStreamItem.Complete containing token usage metadata.
     *
     * @param input The query and shortlisted sources to generate an answer from
     * @return Flow of AnswerStreamItem (Chunk for text, Complete for final token usage)
     */
    fun generateStream(input: PreviewAnswerSynthesisInput): Flow<AnswerStreamItem>
}
