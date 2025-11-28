package io.deepsearch.application.searchorchestrators

import io.deepsearch.application.services.SearchEvent
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.flow.Flow

interface ISearchOrchestrator {
    /**
     * Execute a search query and return a flow of search events.
     * 
     * The flow emits:
     * - SessionCreated: when search starts (includes sessionId)
     * - UrlProcessed: for each URL processed (optional, mainly for live crawling)
     * - ShortlistUpdated: when source shortlist changes (optional)
     * - SessionCompleted: when search finishes with answer
     * - SessionError: if an error occurs
     * 
     * For blocking execution: collect the flow until SessionCompleted/SessionError
     * For streaming: subscribe and process events as they arrive
     */
    fun execute(searchQuery: SearchQuery, maxCacheAge: Long? = null, apiKeyId: ApiKeyId): Flow<SearchEvent>
}