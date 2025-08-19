package io.deepsearch.domain.searchstrategies.googlesearch

import io.deepsearch.domain.agents.IGoogleCombinedSearchAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.searchstrategies.ISearchStrategy

interface IGoogleSearchStrategy : ISearchStrategy {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

/**
 * Performs a Google search + Url Context, powered by Google Gemini.
 *
 * Google has a long history of being the best search engine in the world, so we will leverage it.
 * Use this as a benchmark.
 */
class GoogleSearchStrategy(
    private val googleCombinedSearchAgent: IGoogleCombinedSearchAgent
) : IGoogleSearchStrategy {

    override suspend fun execute(searchQuery: SearchQuery): SearchResult {
        val output = googleCombinedSearchAgent.generate(
            IGoogleCombinedSearchAgent.GoogleCombinedSearchInput(searchQuery)
        )
        return output.searchResult
    }
}