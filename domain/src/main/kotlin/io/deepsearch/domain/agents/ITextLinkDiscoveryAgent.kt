package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebpageLink

/**
 * Input for text-based link discovery.
 * 
 * @param text The text content to analyze for URLs
 * @param sourceUrl URL context for domain filtering
 * @param query The query to determine link relevance
 */
data class TextLinkDiscoveryInput(
    val text: String,
    val sourceUrl: String,
    val query: String
) : IAgent.IAgentInput

/**
 * Output from text link discovery.
 */
data class TextLinkDiscoveryOutput(
    val links: List<WebpageLink>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput {
    companion object {
        fun empty() = TextLinkDiscoveryOutput(
            links = emptyList(),
            tokenUsage = TokenUsageMetrics.empty()
        )
    }
}

/**
 * Agent that discovers relevant URLs from text content (e.g., file search results, markdown).
 * 
 * Uses LLM to analyze text content and extract URLs that are relevant to the query.
 * Filters to same-domain URLs and provides reasoning for relevance.
 */
interface ITextLinkDiscoveryAgent :
    IAgent<TextLinkDiscoveryInput, TextLinkDiscoveryOutput> {

    override suspend fun generate(input: TextLinkDiscoveryInput): TextLinkDiscoveryOutput
}

