package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebsiteContext

/**
 * Input for simple query breakdown agent.
 * Uses cached website context to understand query scope.
 * 
 * @property searchQuery The original user query
 * @property websiteContext Cached context about the target website/page
 */
data class SimpleQueryBreakdownInput(
    val searchQuery: SearchQuery,
    val websiteContext: WebsiteContext
) : IAgent.IAgentInput

/**
 * Output from simple query breakdown agent.
 * Contains the expanded query with context baked in, plus fulfillment requirements.
 * 
 * @property expandedQuery Query rewritten with context for clarity
 * @property fulfillmentRequirements Atomic requirements that must ALL be satisfied
 * @property followUpQueries Suggested queries for early link discovery
 * @property tokenUsage Token usage metrics for this call
 */
data class SimpleQueryBreakdownOutput(
    val expandedQuery: String,
    val fulfillmentRequirements: List<String>,
    val followUpQueries: List<String> = emptyList(),
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that expands a query using cached website context.
 * 
 * Fast path when context is already cached:
 * - Uses cached title/description/summary to understand scope
 * - Expands ambiguous queries with context
 * - Generates atomic fulfillment requirements
 * 
 * Example:
 * - Query: "What's the pricing?"
 * - Context: "Stripe Pricing - Payment processing fees"
 * - Output: "What are Stripe's pricing and fees for payment processing?"
 *   with requirements like ["Transaction fees", "Monthly fees", "Volume discounts"]
 */
interface ISimpleQueryBreakdownAgent : IAgent<SimpleQueryBreakdownInput, SimpleQueryBreakdownOutput> {
    override suspend fun generate(input: SimpleQueryBreakdownInput): SimpleQueryBreakdownOutput
}

