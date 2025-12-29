package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.flow.Flow

/**
 * Input for preview answer synthesis agent.
 * Provides query and classified sources from the preview classification agent.
 */
data class PreviewAnswerSynthesisInput(
    val query: String,
    val sourceClassifications: List<ClassifiedSource>
) : IAgent.IAgentInput

/**
 * Output from preview answer synthesis agent.
 * Contains the generated answer with confidence metrics and reasoning.
 */
data class PreviewAnswerSynthesisOutput(
    val answer: String,
    val answerFound: Boolean,
    val reasoning: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Items emitted during streaming preview answer synthesis.
 */
sealed class PreviewAnswerStreamItem {
    /**
     * A chunk of answer text (streamed as generated).
     */
    data class Chunk(val text: String) : PreviewAnswerStreamItem()

    /**
     * Emitted after all chunks, contains final metadata.
     */
    data class Complete(
        val tokenUsage: TokenUsageMetrics,
        val answerFound: Boolean,
        val reasoning: String
    ) : PreviewAnswerStreamItem()
}

/**
 * Agent that generates answers from classified preview sources.
 * 
 * This agent performs internal filtering:
 * - Only uses facts where isInTable=false AND classification=OFFICIAL_LIVING_DOC
 * - Returns answerFound=false if no valid facts remain after filtering
 * 
 * The preview answer is meant as an early exit for simple static content.
 * It is CONSERVATIVE - prefers "I cannot answer" over guessing.
 */
interface IPreviewAnswerSynthesisAgent : IAgent<PreviewAnswerSynthesisInput, PreviewAnswerSynthesisOutput> {
    override suspend fun generate(input: PreviewAnswerSynthesisInput): PreviewAnswerSynthesisOutput

    /**
     * Stream answer generation, emitting answer text chunks as they are generated.
     * The last emission is a PreviewAnswerStreamItem.Complete containing token usage,
     * answerFound flag, and reasoning.
     *
     * @param input The query and classified sources to generate an answer from
     * @return Flow of PreviewAnswerStreamItem (Chunk for text, Complete for final metadata)
     */
    fun generateStream(input: PreviewAnswerSynthesisInput): Flow<PreviewAnswerStreamItem>
}

