package io.deepsearch.domain.services

import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent.AggregateSearchResultsInput
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult

interface IAggregateSearchResultsService {
    suspend fun aggregate(searchQuery: SearchQuery, searchResults: List<SearchResult>): SearchResult
}

class AggregateSearchResultsService(
    private val aggregateSearchResultsAgent: IAggregateSearchResultsAgent
) : IAggregateSearchResultsService {

    override suspend fun aggregate(
        searchQuery: SearchQuery,
        searchResults: List<SearchResult>
    ): SearchResult {
        return aggregateSearchResultsAgent
            .generate(AggregateSearchResultsInput(searchQuery, searchResults))
            .aggregatedResult
    }
}


