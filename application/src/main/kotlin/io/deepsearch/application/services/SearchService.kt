package io.deepsearch.application.services

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface ISearchService {
    suspend fun searchWebsite(
        query: String, 
        url: String, 
        sitemapUrl: String? = null,
        maxUrls: Int? = null,
        searchDurationSeconds: Int? = null,
        cacheExpiryMs: Long? = null
    ): SearchResult
}

class SearchService(
    private val agenticBrowserSearchOrchestrator: IAgenticBrowserSearchOrchestrator
) : ISearchService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun searchWebsite(
        query: String, 
        url: String, 
        sitemapUrl: String?,
        maxUrls: Int?,
        searchDurationSeconds: Int?,
        cacheExpiryMs: Long?
    ): SearchResult {
        val searchQuery = SearchQuery(query, url, sitemapUrl)
        val effectiveCacheExpiryMs = cacheExpiryMs ?: 604800000L // Default 7 days
        return agenticBrowserSearchOrchestrator.execute(searchQuery, maxUrls, searchDurationSeconds, effectiveCacheExpiryMs)
    }
}