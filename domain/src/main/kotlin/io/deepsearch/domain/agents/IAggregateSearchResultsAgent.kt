package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class AggregateSearchResultsInput(
    val searchQuery: SearchQuery,
    val searchResults: List<SearchResult>
) : IAgent.IAgentInput

data class AggregateSearchResultsOutput(
    val aggregatedResult: SearchResult,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IAggregateSearchResultsAgent :
    IAgent<AggregateSearchResultsInput, AggregateSearchResultsOutput> {

    override suspend fun generate(input: AggregateSearchResultsInput): AggregateSearchResultsOutput
}


