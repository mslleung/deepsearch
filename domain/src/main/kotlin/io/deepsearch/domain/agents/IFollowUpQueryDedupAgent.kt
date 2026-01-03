package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

/**
 * Input for the follow-up query deduplication agent.
 * 
 * @property candidateQueries Follow-up queries suggested by parallel synthesis agents
 * @property previouslySearchedQueries Queries that have already been searched (for dedup context)
 * @property originalQuery The original user query
 */
data class FollowUpQueryDedupInput(
    val candidateQueries: List<String>,
    val previouslySearchedQueries: List<String>,
    val originalQuery: String
) : IAgent.IAgentInput

/**
 * Output from the follow-up query deduplication agent.
 * 
 * @property dedupedQueries Queries that are semantically distinct from previously searched queries
 * @property tokenUsage Token usage metrics for this dedup call
 */
data class FollowUpQueryDedupOutput(
    val dedupedQueries: List<String>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that deduplicates follow-up queries using semantic similarity.
 * 
 * This agent filters out queries that are semantically similar to:
 * 1. The original user query
 * 2. Previously searched queries
 * 3. Other candidate queries (keeping only one from each semantic group)
 * 
 * Uses LLM-based understanding to detect semantic duplicates that simple
 * string matching would miss (e.g., "stripe pricing" vs "pricing for stripe").
 */
interface IFollowUpQueryDedupAgent : IAgent<FollowUpQueryDedupInput, FollowUpQueryDedupOutput> {
    override suspend fun generate(input: FollowUpQueryDedupInput): FollowUpQueryDedupOutput
}

