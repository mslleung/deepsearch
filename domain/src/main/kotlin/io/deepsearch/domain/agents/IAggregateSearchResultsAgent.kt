package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult

interface IAggregateSearchResultsAgent :
    IAgent<IAggregateSearchResultsAgent.AggregateSearchResultsInput, IAggregateSearchResultsAgent.AggregateSearchResultsOutput> {

    data class AggregateSearchResultsInput(
        val searchQuery: SearchQuery,
        val searchResults: List<SearchResult>) : IAgent.IAgentInput

    data class AggregateSearchResultsOutput(val aggregatedResult: SearchResult) : IAgent.IAgentOutput

    override suspend fun generate(input: AggregateSearchResultsInput): AggregateSearchResultsOutput
}


