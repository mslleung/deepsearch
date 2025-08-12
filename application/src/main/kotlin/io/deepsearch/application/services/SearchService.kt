package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.services.IAgenticSearchService
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface ISearchService {
    suspend fun searchWebsite(query: String, url: String): SearchResult
}

class SearchService(
    private val agenticSearchService: IAgenticSearchService
) : ISearchService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun searchWebsite(query: String, url: String): SearchResult {
        val searchQuery = SearchQuery(query, url)
        return agenticSearchService.performSearch(searchQuery)
    }
}