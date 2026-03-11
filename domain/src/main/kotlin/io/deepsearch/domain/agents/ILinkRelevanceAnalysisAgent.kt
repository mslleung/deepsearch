package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebpageLink

data class LinkRelevanceAnalysisInput(
    val html: String,
    val query: String,
    val url: String,
    val excludeUrls: Set<String> = emptySet()
) : IAgent.IAgentInput

data class LinkRelevanceAnalysisOutput(
    val links: List<WebpageLink>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface ILinkRelevanceAnalysisAgent :
    IAgent<LinkRelevanceAnalysisInput, LinkRelevanceAnalysisOutput> {

    override suspend fun generate(input: LinkRelevanceAnalysisInput): LinkRelevanceAnalysisOutput
}

