package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.flow.Flow

/**
 * Input for streaming answer synthesis agent.
 * Provides query and shortlisted sources (with extracted facts) to generate an answer from.
 */
data class StreamingAnswerSynthesisInput(
    val query: String,
    val shortlistedSources: List<ShortlistedSource>
) : IAgent.IAgentInput

/**
 * Output from streaming answer synthesis agent.
 * Contains the generated comprehensive answer, whether an answer was found, reasoning, and referenced image IDs.
 */
data class StreamingAnswerSynthesisOutput(
    val answer: String,
    val answerFound: Boolean,
    val reasoning: String,
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
     * Emitted after all chunks, contains token usage, answerFound flag, reasoning, and referenced image IDs.
     */
    data class Complete(
        val tokenUsage: TokenUsageMetrics,
        val answerFound: Boolean,
        val reasoning: String,
        val imageIds: List<String> = emptyList()
    ) : StreamingAnswerStreamItem()
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

