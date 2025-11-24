package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

/**
 * Input for streaming source shortlist agent.
 * Provides current shortlist and new batch of sources to evaluate.
 */
data class StreamingSourceShortlistInput(
    val query: String,
    val currentShortlist: List<ShortlistedSource>,
    val newMarkdownBatch: List<MarkdownSource>
) : IAgent.IAgentInput

/**
 * Output from streaming source shortlist agent.
 * Contains updated shortlist, sufficiency decision, and reasoning.
 */
data class StreamingSourceShortlistOutput(
    val updatedShortlist: List<ShortlistedSource>,
    val isGoodEnough: Boolean,
    val reason: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that curates a shortlist of high-quality sources for answering a query.
 * Evaluates sources based on content relevance, temporal relevance, and authority.
 * Handles information conflicts by keeping the most relevant sources.
 */
interface IStreamingSourceShortlistAgent : IAgent<StreamingSourceShortlistInput, StreamingSourceShortlistOutput> {
    override suspend fun generate(input: StreamingSourceShortlistInput): StreamingSourceShortlistOutput
}

