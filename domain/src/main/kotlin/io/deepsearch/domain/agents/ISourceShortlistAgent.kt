package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

/**
 * Input for source shortlist agent.
 * Provides current shortlist and new batch of markdown sources to evaluate.
 */
data class SourceShortlistInput(
    val query: String,
    val currentShortlist: List<ShortlistedSource>,
    val newMarkdownBatch: List<MarkdownSource>
) : IAgent.IAgentInput

/**
 * Output from source shortlist agent.
 * Contains updated shortlist with extracted facts, sufficiency decision, and reasoning.
 */
data class SourceShortlistOutput(
    val updatedShortlist: List<ShortlistedSource>,
    val isGoodEnough: Boolean,
    val reason: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that curates a shortlist of high-quality sources for answering a query.
 * 
 * For each source, the agent:
 * - Extracts facts relevant to the query
 * - Classifies the source type (OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, OTHERS)
 * - Determines answer type and temporal metadata
 * 
 * The agent also determines if the current shortlist is sufficient (isGoodEnough)
 * to answer the query comprehensively.
 */
interface ISourceShortlistAgent : IAgent<SourceShortlistInput, SourceShortlistOutput> {
    override suspend fun generate(input: SourceShortlistInput): SourceShortlistOutput
}

