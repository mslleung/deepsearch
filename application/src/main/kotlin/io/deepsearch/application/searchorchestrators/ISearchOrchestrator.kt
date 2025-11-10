package io.deepsearch.application.searchorchestrators

import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult

interface ISearchOrchestrator {
    suspend fun execute(searchQuery: SearchQuery, maxUrls: Int? = null, searchDurationSeconds: Int? = null, cacheExpiryMs: Long = 604800000L): SearchResult
}