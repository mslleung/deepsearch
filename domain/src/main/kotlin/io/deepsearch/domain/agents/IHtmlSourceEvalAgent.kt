package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.UrlContentResult

/**
 * Input for HTML source evaluation agent.
 * Provides a single HTML preview source to evaluate and extract facts from.
 * 
 * The agent internally appends `site:<domain>` to the query for better context.
 * 
 * @property searchQuery The original user query
 * @property htmlSource The HTML preview source to evaluate
 * @property expandedQuery Optional context-aware expanded query (preferred over raw query)
 * @property fulfillmentRequirements Optional list of requirements that must be satisfied
 */
data class HtmlSourceEvalInput(
    val searchQuery: SearchQuery,
    val htmlSource: UrlContentResult.HtmlPreview,
    val expandedQuery: String? = null,
    val fulfillmentRequirements: List<String> = emptyList()
) : IAgent.IAgentInput {
    /**
     * Returns the query to use for evaluation.
     * Prefers expandedQuery if available, otherwise uses the raw query.
     */
    val effectiveQuery: String get() = expandedQuery ?: searchQuery.query
}

/**
 * Output from HTML source evaluation agent.
 * Contains the evaluated source with extracted facts, or null if no relevant facts found.
 * 
 * Facts where isInTable=true are filtered out before returning, since table
 * data in HTML previews may be inaccurate.
 * 
 * @property evaluatedSource The evaluated source with extracted facts, or null if not relevant
 * @property tokenUsage Token usage metrics for this evaluation
 */
data class HtmlSourceEvalOutput(
    val evaluatedSource: EvaluatedSource?,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that evaluates a single HTML preview source and extracts facts.
 * 
 * For the source, the agent:
 * - Determines the intention (purpose) of the webpage
 * - Assesses relevance to the query
 * - Extracts facts relevant to the query
 * - Marks whether each fact comes from a table/grid (isInTable)
 * 
 * Facts from tables (isInTable=true) are filtered out before returning,
 * as table data in HTML previews may be inaccurate.
 * 
 * This is a stateless agent that processes one source at a time, enabling parallel processing.
 * Used in the preview path for early exit with conservative fact extraction.
 */
interface IHtmlSourceEvalAgent : IAgent<HtmlSourceEvalInput, HtmlSourceEvalOutput> {
    override suspend fun generate(input: HtmlSourceEvalInput): HtmlSourceEvalOutput
}
