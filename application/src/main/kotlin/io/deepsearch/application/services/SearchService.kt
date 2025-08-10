package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.services.IAgenticSearchService
import org.koin.core.component.KoinComponent
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface ISearchService {
    suspend fun searchWebsite(query: String, url: String): String
}

class SearchService(
    private val agenticSearchService: IAgenticSearchService
) : ISearchService, KoinComponent {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun searchWebsite(query: String, url: String): String {
        val searchQuery = SearchQuery(query, url)
        return agenticSearchService.performSearch(searchQuery)
    }
} 