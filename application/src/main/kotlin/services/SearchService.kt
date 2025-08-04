package io.deepsearch.application.services

import io.deepsearch.domain.services.IAgenticSearchService
import io.deepsearch.domain.valueobjects.SearchQuery
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import org.slf4j.Logger

interface ISearchService {
    suspend fun searchWebsite(query: String, url: String): String
}

class SearchService(
    private val agenticSearchService: IAgenticSearchService
) : ISearchService, KoinComponent {
    private val logger: Logger by inject { parametersOf(SearchService::class) }

    override suspend fun searchWebsite(query: String, url: String): String {
        val searchQuery = SearchQuery(query, url)
        return agenticSearchService.performSearch(searchQuery)
    }
} 