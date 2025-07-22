package io.deepsearch.application.services

import io.deepsearch.domain.services.SearchService
import io.deepsearch.domain.valueobjects.SearchQuery
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import org.slf4j.Logger

class SearchService(
    private val searchService: SearchService
) : KoinComponent {
    private val logger: Logger by inject { parametersOf(SearchService::class) }

    suspend fun searchWebsite(query: String, url: String): String {
        val searchQuery = SearchQuery(query, url)
        return searchService.performSearch(searchQuery)
    }
} 