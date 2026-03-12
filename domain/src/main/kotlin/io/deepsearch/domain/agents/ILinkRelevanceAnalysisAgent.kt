package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebpageLink

data class LinkRelevanceAnalysisInput(
    val html: String,
    val query: String,
    val url: String,
    /**
     * Thread-safe shared set for cross-page link deduplication during concurrent processing.
     * When provided, each extracted URL is atomically claimed via [MutableSet.add];
     * only newly claimed URLs are included in the LLM prompt, preventing the same link
     * from being analyzed on multiple pages processed in parallel.
     */
    val sharedEvaluatedUrls: MutableSet<String>? = null
) : IAgent.IAgentInput

data class LinkRelevanceAnalysisOutput(
    val links: List<WebpageLink>,
    val allEvaluatedUrls: Set<String>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface ILinkRelevanceAnalysisAgent :
    IAgent<LinkRelevanceAnalysisInput, LinkRelevanceAnalysisOutput> {

    override suspend fun generate(input: LinkRelevanceAnalysisInput): LinkRelevanceAnalysisOutput
}

