package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.UrlContentResult

/**
 * Input for source evaluation agent (preview path).
 * 
 * The source contains extracted sentences (plain text), not raw HTML.
 * This is token-efficient and naturally filters out tabular data
 * since table cells are fragments, not complete sentences.
 * 
 * The agent internally appends `site:<domain>` to the query for better context.
 * Domain is extracted from the source URL.
 * 
 * @property htmlSource The preview source with extracted sentences (despite the name, contains plain text)
 * @property expandedQuery Context-aware expanded query to evaluate against
 * @property fulfillmentRequirements List of requirements that must be satisfied
 */
data class HtmlSourceEvalInput(
    val htmlSource: UrlContentResult.HtmlPreview,
    val expandedQuery: String,
    val fulfillmentRequirements: List<String> = emptyList()
) : IAgent.IAgentInput

/**
 * Output from source evaluation agent.
 * Contains the evaluated source with extracted facts, or null if no relevant facts found.
 * 
 * @property evaluatedSource The evaluated source with extracted facts, or null if not relevant
 * @property tokenUsage Token usage metrics for this evaluation
 */
data class HtmlSourceEvalOutput(
    val evaluatedSource: EvaluatedSource?,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that evaluates a preview source and extracts facts.
 * 
 * Input is extracted sentences (plain text) which:
 * - Is token-efficient compared to HTML
 * - Naturally filters out tabular data (table cells are fragments, not sentences)
 * - Works across languages via ICU4J sentence detection
 * 
 * For the source, the agent:
 * - Determines the intention (purpose) of the webpage
 * - Assesses relevance to the query
 * - Extracts facts relevant to the query
 * 
 * This is a stateless agent that processes one source at a time, enabling parallel processing.
 * Used in the preview path for early exit with conservative fact extraction.
 */
interface IHtmlSourceEvalAgent : IAgent<HtmlSourceEvalInput, HtmlSourceEvalOutput> {
    override suspend fun generate(input: HtmlSourceEvalInput): HtmlSourceEvalOutput
}
