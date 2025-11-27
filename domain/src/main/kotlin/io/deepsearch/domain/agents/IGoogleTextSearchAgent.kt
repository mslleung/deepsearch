package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class GoogleTextSearchInput(
    val searchQuery: SearchQuery
) : IAgent.IAgentInput

data class GoogleTextSearchOutput(
    /** URLs discovered from the Google text search */
    val answerSources: List<String>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IGoogleTextSearchAgent :
    IAgent<GoogleTextSearchInput, GoogleTextSearchOutput> {

    override suspend fun generate(input: GoogleTextSearchInput): GoogleTextSearchOutput
}


