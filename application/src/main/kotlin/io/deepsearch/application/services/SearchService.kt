package io.deepsearch.application.services

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface ISearchService {
    suspend fun searchWebsite(query: String, url: String, sitemapUrl: String? = null): SearchResult
}

class SearchService(
    private val agenticBrowserSearchOrchestrator: IAgenticBrowserSearchOrchestrator
) : ISearchService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun searchWebsite(query: String, url: String, sitemapUrl: String?): SearchResult {
        val searchQuery = SearchQuery(query, url, sitemapUrl)
        return agenticBrowserSearchOrchestrator.execute(searchQuery)
    }
}