package io.deepsearch.application.searchorchestrators

import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.SearchQuery

interface ISearchOrchestrator {
    /**
     * Execute a search query and return the session ID.
     * The session contains all the search results and can be retrieved via QuerySessionService.
     */
    suspend fun execute(searchQuery: SearchQuery, cacheExpiryMs: Long? = null, apiKeyId: ApiKeyId): String
}