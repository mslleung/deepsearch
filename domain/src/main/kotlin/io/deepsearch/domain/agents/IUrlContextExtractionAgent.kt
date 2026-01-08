package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebsiteContext

/**
 * Input for URL context extraction agent.
 * 
 * @property url The URL to fetch and extract context from
 */
data class UrlContextExtractionInput(
    val url: String
) : IAgent.IAgentInput

/**
 * Output from URL context extraction agent.
 * 
 * @property websiteContext Extracted context about the page (to be cached)
 * @property tokenUsage Token usage metrics for this call
 */
data class UrlContextExtractionOutput(
    val websiteContext: WebsiteContext,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that uses Gemini URL Context tool to fetch and extract website context.
 * 
 * This agent:
 * - Uses the URL Context tool to fetch page content (no JSON mode with tools)
 * - Extracts structured context (title, description, summary)
 * - Returns WebsiteContext for caching and use by QueryBreakdownAgent
 */
interface IUrlContextExtractionAgent : IAgent<UrlContextExtractionInput, UrlContextExtractionOutput> {
    override suspend fun generate(input: UrlContextExtractionInput): UrlContextExtractionOutput
}
