package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebpageLink

data class GoogleSearchLinkDiscoveryInput(
    val searchQuery: SearchQuery
) : IAgent.IAgentInput

data class GoogleSearchLinkDiscoveryOutput(
    val links: List<WebpageLink>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IGoogleSearchLinkDiscoveryAgent :
    IAgent<GoogleSearchLinkDiscoveryInput, GoogleSearchLinkDiscoveryOutput> {

    override suspend fun generate(input: GoogleSearchLinkDiscoveryInput): GoogleSearchLinkDiscoveryOutput
}

