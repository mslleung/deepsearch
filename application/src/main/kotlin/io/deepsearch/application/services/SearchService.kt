package io.deepsearch.application.services

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.cacheonlysearch.ICacheOnlySearchOrchestrator
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchMode
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.proxy.ProxyConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
        userId: UserId,
        languagePattern: String? = null,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT
    ): QuerySessionDetail

    /**
     * Execute a search and return a flow of events.
     * The flow emits events as the search progresses and completes with SessionCompleted or SessionError.
     * 
     * For streaming clients: subscribe and forward events to the client
     * For blocking clients: use searchWebsite() instead
     */
    suspend fun executeStreaming(
        query: String,
        url: String,
        maxCacheAge: Long? = null,
        mode: SearchMode = SearchMode.LIVE_CRAWLING,
        apiKeyId: ApiKeyId,
        userId: UserId,
        languagePattern: String? = null,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT
    ): Flow<SearchEvent>
}

class SearchService(
    private val applicationScope: IApplicationCoroutineScope,
    private val agenticBrowserSearchOrchestrator: IAgenticBrowserSearchOrchestrator,
    private val cacheOnlySearchOrchestrator: ICacheOnlySearchOrchestrator,
    private val querySessionService: IQuerySessionService,
    private val proxySettingsService: IProxySettingsService
) : ISearchService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun searchWebsite(
        query: String, 
        url: String, 
        maxCacheAge: Long?,
        mode: SearchMode,
        apiKeyId: ApiKeyId,
        userId: UserId,
        languagePattern: String?,
        ocrLanguage: OcrLanguage
    ): QuerySessionDetail {
        val searchQuery = SearchQuery(query, url, languagePattern, ocrLanguage)
        
        // Resolve user's proxy configuration for this URL
        // Custom/Premium proxies are used directly; None triggers adaptive bypass strategy
        val proxyConfig = proxySettingsService.resolveProxyForUrl(userId, url)
        
        logger.debug(
            "Executing {} search for query: {} with proxy: {} language pattern: {} and OCR language: {}", 
            mode, query, proxyConfig::class.simpleName, languagePattern, ocrLanguage
        )
        
        val orchestrator = when (mode) {
            SearchMode.LIVE_CRAWLING -> agenticBrowserSearchOrchestrator
            SearchMode.CACHE_ONLY -> cacheOnlySearchOrchestrator
        }
        
        // Collect the flow until we get a terminal event
        val completionDeferred = CompletableDeferred<SearchEvent.SessionCompleted>()

        val job = applicationScope.scope.launch {
            orchestrator.execute(searchQuery, maxCacheAge, apiKeyId, proxyConfig)
                .filterIsInstance<SearchEvent.SessionCompleted>()
                .onEach {
                    completionDeferred.complete(it)
                }
                .first()
        }

        val completionEvent = completionDeferred.await()
        job.cancel()
        
        return querySessionService.getSessionDetail(completionEvent.sessionId, userId)
    }

    override suspend fun executeStreaming(
        query: String,
        url: String,
        maxCacheAge: Long?,
        mode: SearchMode,
        apiKeyId: ApiKeyId,
        userId: UserId,
        languagePattern: String?,
        ocrLanguage: OcrLanguage
    ): Flow<SearchEvent> {
        val searchQuery = SearchQuery(query, url, languagePattern, ocrLanguage)
        
        // Resolve user's proxy configuration for this URL
        val proxyConfig = proxySettingsService.resolveProxyForUrl(userId, url)
        
        logger.debug(
            "Starting streaming {} search for query: {} with proxy: {} language pattern: {} and OCR language: {}", 
            mode, query, proxyConfig::class.simpleName, languagePattern, ocrLanguage
        )
        
        val orchestrator = when (mode) {
            SearchMode.LIVE_CRAWLING -> agenticBrowserSearchOrchestrator
            SearchMode.CACHE_ONLY -> cacheOnlySearchOrchestrator
        }
        
        return orchestrator.execute(searchQuery, maxCacheAge, apiKeyId, proxyConfig)
    }
}
