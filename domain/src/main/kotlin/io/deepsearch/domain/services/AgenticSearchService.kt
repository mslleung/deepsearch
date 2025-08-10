package io.deepsearch.domain.services

import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

interface IAgenticSearchService {
    suspend fun performSearch(searchQuery: SearchQuery): String
}

class AgenticSearchService(
    private val queryExpansionService: QueryExpansionService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : IAgenticSearchService {

    override suspend fun performSearch(searchQuery: SearchQuery): String {
        val (query, url) = searchQuery

        val expandedQueries = queryExpansionService.expandQuery(query)

        return ""
    }

}