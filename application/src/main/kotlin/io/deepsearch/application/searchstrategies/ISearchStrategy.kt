package io.deepsearch.application.searchstrategies

import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult

interface ISearchStrategy {
    suspend fun execute(searchQuery: SearchQuery): SearchResult
}