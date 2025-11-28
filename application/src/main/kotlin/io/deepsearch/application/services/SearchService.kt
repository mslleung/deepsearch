package io.deepsearch.application.services

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.cacheonlysearch.ICacheOnlySearchOrchestrator
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchMode
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface ISearchService {
    /**
     * Execute a search and return the query session detail with all related data.
     * Blocks until the search is complete.
     */
    suspend fun searchWebsite(
        query: String, 
        url: String, 
        maxCacheAge: Long? = null,
        mode: SearchMode = SearchMode.LIVE_CRAWLING,
        apiKeyId: ApiKeyId,
        userId: UserId
    ): QuerySessionDetail

    /**
     * Execute a search and return a flow of events.
     * The flow emits events as the search progresses and completes with SessionCompleted or SessionError.
     * 
     * For streaming clients: subscribe and forward events to the client
     * For blocking clients: use searchWebsite() instead
     */
    fun executeStreaming(
        query: String,
        url: String,
        maxCacheAge: Long? = null,
        mode: SearchMode = SearchMode.LIVE_CRAWLING,
        apiKeyId: ApiKeyId
    ): Flow<SearchEvent>
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
        
        logger.debug("Executing {} search for query: {}", mode, query)
        
        val orchestrator = when (mode) {
            SearchMode.LIVE_CRAWLING -> agenticBrowserSearchOrchestrator
            SearchMode.CACHE_ONLY -> cacheOnlySearchOrchestrator
        }
        
        // Collect the flow until we get a terminal event
        var sessionId: QuerySessionId? = null
        
        orchestrator.execute(searchQuery, maxCacheAge, apiKeyId)
            .onEach { event ->
                // Capture session ID from the first event
                if (sessionId == null && event is SearchEvent.SessionCreated) {
                    sessionId = QuerySessionId(event.sessionId)
                }
            }
            .filterIsInstance<SearchEvent.SessionCompleted>()
            .first()
        
        return querySessionService.getSessionDetail(sessionId!!, userId)
    }

    override fun executeStreaming(
        query: String,
        url: String,
        maxCacheAge: Long?,
        mode: SearchMode,
        apiKeyId: ApiKeyId
    ): Flow<SearchEvent> {
        val searchQuery = SearchQuery(query, url)
        
        logger.debug("Starting streaming {} search for query: {}", mode, query)
        
        val orchestrator = when (mode) {
            SearchMode.LIVE_CRAWLING -> agenticBrowserSearchOrchestrator
            SearchMode.CACHE_ONLY -> cacheOnlySearchOrchestrator
        }
        
        return orchestrator.execute(searchQuery, maxCacheAge, apiKeyId)
    }
}
