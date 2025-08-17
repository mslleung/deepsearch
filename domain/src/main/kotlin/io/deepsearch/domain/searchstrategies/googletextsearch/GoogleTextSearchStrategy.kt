package io.deepsearch.domain.searchstrategies.googletextsearch

import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.searchstrategies.ISearchStrategy

interface IGoogleTextSearchStrategy : ISearchStrategy {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

/**
 * Performs a simple Google search, powered by Google.
 *
 * Google has a long history of being the best search engine in the world, so we will leverage it.
 * Note that Google search is not as comprehensive as agentic search.
 */
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