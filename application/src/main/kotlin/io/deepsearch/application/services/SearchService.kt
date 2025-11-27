package io.deepsearch.application.services

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.cacheonlysearch.ICacheOnlySearchOrchestrator
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchMode
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
        maxCacheAge: Long? = null,
        mode: SearchMode = SearchMode.LIVE_CRAWLING,
        apiKeyId: ApiKeyId,
        userId: UserId
    ): QuerySessionDetail
}

class SearchService(
    private val agenticBrowserSearchOrchestrator: IAgenticBrowserSearchOrchestrator,
    private val cacheOnlySearchOrchestrator: ICacheOnlySearchOrchestrator,
    private val querySessionService: IQuerySessionService
) : ISearchService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun searchWebsite(
        query: String, 
        url: String, 
        maxCacheAge: Long?,
        mode: SearchMode,
        apiKeyId: ApiKeyId,
        userId: UserId
    ): QuerySessionDetail {
        val searchQuery = SearchQuery(query, url)
        
        val sessionId: QuerySessionId = when (mode) {
            SearchMode.LIVE_CRAWLING -> {
                logger.debug("Executing live-crawling search for query: {}", query)
                agenticBrowserSearchOrchestrator.execute(searchQuery, maxCacheAge, apiKeyId)
            }
            SearchMode.CACHE_ONLY -> {
                logger.debug("Executing cache-only search for query: {}", query)
                cacheOnlySearchOrchestrator.execute(searchQuery, maxCacheAge, apiKeyId)
            }
        }
        
        return querySessionService.getSessionDetail(sessionId, userId)
    }
}
