package io.deepsearch.domain.services

import io.deepsearch.domain.models.valueobjects.SearchQuery

interface IGoogleSearchService {
    suspend fun performTextSearch(searchQuery: SearchQuery): String
}

class GoogleSearchService: IGoogleSearchService {

    override suspend fun performTextSearch(searchQuery: SearchQuery): String {


        // TODO get text search results based on the search query for our ai
        return ""
    }
}