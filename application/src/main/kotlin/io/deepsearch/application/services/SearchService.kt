package io.deepsearch.application.services

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.UserId
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface ISearchService {
    /**
     * Execute a search and return the query session detail with all related data.
     */
    suspend fun searchWebsite(
        query: String, 
        url: String, 
        cacheExpiryMs: Long? = null,
        apiKeyId: ApiKeyId,
        userId: UserId
    ): QuerySessionDetail
}

class SearchService(
    private val agenticBrowserSearchOrchestrator: IAgenticBrowserSearchOrchestrator,
    private val querySessionService: IQuerySessionService
) : ISearchService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun searchWebsite(
        query: String, 
        url: String, 
        cacheExpiryMs: Long?,
        apiKeyId: ApiKeyId,
        userId: UserId
    ): QuerySessionDetail {
        val searchQuery = SearchQuery(query, url)
        val sessionId = agenticBrowserSearchOrchestrator.execute(searchQuery, cacheExpiryMs, apiKeyId)
        return querySessionService.getSessionDetail(sessionId, userId)
    }
}
