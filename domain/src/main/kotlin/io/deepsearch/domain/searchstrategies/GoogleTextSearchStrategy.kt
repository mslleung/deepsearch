package io.deepsearch.domain.searchstrategies

import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult

interface IGoogleTextSearchStrategy : ISearchStrategy {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

class GoogleTextSearchStrategy(
    private val googleTextSearchAgent: IGoogleTextSearchAgent
) : IGoogleTextSearchStrategy {

    override suspend fun execute(searchQuery: SearchQuery): SearchResult {
        val output = googleTextSearchAgent.generate(
            IGoogleTextSearchAgent.GoogleTextSearchInput(searchQuery)
        )
        return output.searchResult
    }
}