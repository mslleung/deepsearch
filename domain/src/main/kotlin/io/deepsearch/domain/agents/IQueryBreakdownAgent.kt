package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SessionHistory
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebsiteContext

/**
 * Input for query breakdown agent.
 * Uses website context to understand query scope.
 * 
 * When sessionHistory is provided (session continuation), the agent:
 * - Understands what topics have been explored in prior sessions
 * - Transforms the query to target NEW information (the delta)
 * - Generates requirements for information not yet covered
 * - Generates follow-up queries that explore unexplored areas
 * 
 * @property searchQuery The original user query
 * @property websiteContext Context about the target website/page
 * @property sessionHistory History of prior sessions for context-aware processing (optional)
 */
data class QueryBreakdownInput(
    val searchQuery: SearchQuery,
    val websiteContext: WebsiteContext,
    val sessionHistory: SessionHistory = SessionHistory.empty()
) : IAgent.IAgentInput

/**
 * Output from query breakdown agent.
 * Contains the expanded query with context baked in, plus fulfillment requirements.
 * 
 * @property expandedQuery Query rewritten with context for clarity
 * @property fulfillmentRequirements Atomic requirements that must ALL be satisfied
 * @property followUpQueries Suggested queries for early link discovery
 * @property tokenUsage Token usage metrics for this call
 */
data class QueryBreakdownOutput(
    val expandedQuery: String,
    val fulfillmentRequirements: List<String>,
    val followUpQueries: List<String> = emptyList(),
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that expands a query using website context.
 * 
 * - Uses title/description/summary to understand scope
 * - Expands ambiguous queries with context
 * - Generates atomic fulfillment requirements
 * 
 * Example:
 * - Query: "What's the pricing?"
 * - Context: "Stripe Pricing - Payment processing fees"
 * - Output: "What are Stripe's pricing and fees for payment processing?"
 *   with requirements like ["Transaction fees", "Monthly fees", "Volume discounts"]
 */
interface IQueryBreakdownAgent : IAgent<QueryBreakdownInput, QueryBreakdownOutput> {
    override suspend fun generate(input: QueryBreakdownInput): QueryBreakdownOutput
}
