package io.deepsearch.application.services

import io.deepsearch.application.searchstrategies.agenticbrowsersearch.IAgenticBrowserSearchStrategy
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface ISearchService {
    suspend fun searchWebsite(query: String, url: String): SearchResult
}

class SearchService(
    private val agenticBrowserSearchStrategy: IAgenticBrowserSearchStrategy
) : ISearchService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun searchWebsite(query: String, url: String): SearchResult {
        val searchQuery = SearchQuery(query, url)
        return agenticBrowserSearchStrategy.execute(searchQuery)
    }
}