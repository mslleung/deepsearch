package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebsiteContext

/**
 * Input for full query breakdown agent.
 * Uses Gemini URL Context tool to fetch and understand the page content.
 * 
 * @property searchQuery The original user query
 * @property url The URL of the page to analyze
 */
data class FullQueryBreakdownInput(
    val searchQuery: SearchQuery,
    val url: String
) : IAgent.IAgentInput

/**
 * Output from full query breakdown agent.
 * Contains extracted website context, expanded query, and fulfillment requirements.
 * 
 * @property websiteContext Extracted context about the page (to be cached)
 * @property expandedQuery Query rewritten with context for clarity
 * @property fulfillmentRequirements Atomic requirements that must ALL be satisfied
 * @property tokenUsage Token usage metrics for this call
 */
data class FullQueryBreakdownOutput(
    val websiteContext: WebsiteContext,
    val expandedQuery: String,
    val fulfillmentRequirements: List<String>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that extracts website context from HTML and expands query.
 * 
 * Cold path when context is not cached:
 * - Analyzes HTML content to understand page purpose/scope
 * - Extracts context summary for caching
 * - Expands ambiguous queries with extracted context
 * - Generates atomic fulfillment requirements
 * 
 * The extracted WebsiteContext should be cached for future queries on the same URL.
 */
interface IFullQueryBreakdownAgent : IAgent<FullQueryBreakdownInput, FullQueryBreakdownOutput> {
    override suspend fun generate(input: FullQueryBreakdownInput): FullQueryBreakdownOutput
}

