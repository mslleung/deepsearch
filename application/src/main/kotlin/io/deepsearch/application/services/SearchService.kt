package io.deepsearch.application.services

import io.deepsearch.application.searchorchestrators.agenticbrowsersearch.IAgenticBrowserSearchOrchestrator
import io.deepsearch.application.searchorchestrators.cacheonlysearch.ICacheOnlySearchOrchestrator
import io.deepsearch.domain.agents.PriorSessionContext
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
     * 
     * @param continueSessionId Optional session ID to continue from (for session continuation).
     *                          When provided, the previous session's query and answer are passed
     *                          to the answer synthesis agent to avoid repeating information.
     */
    suspend fun searchWebsite(
        query: String, 
        url: String, 
        maxCacheAge: Long? = null,
        mode: SearchMode = SearchMode.LIVE_CRAWLING,
        apiKeyId: ApiKeyId,
        userId: UserId,
        languagePattern: String? = null,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT,
        includeImages: Boolean = false,
        continueSessionId: String? = null
    ): QuerySessionDetail

    /**
     * Execute a search and return a flow of events.
     * The flow emits events as the search progresses and completes with SessionCompleted or SessionError.
     * 
     * For streaming clients: subscribe and forward events to the client
     * For blocking clients: use searchWebsite() instead
     * 
     * @param continueSessionId Optional session ID to continue from (for session continuation).
     */
    suspend fun executeStreaming(
        query: String,
        url: String,
        maxCacheAge: Long? = null,
        mode: SearchMode = SearchMode.LIVE_CRAWLING,
        apiKeyId: ApiKeyId,
        userId: UserId,
        languagePattern: String? = null,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT,
        includeImages: Boolean = false,
        continueSessionId: String? = null
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
        ocrLanguage: OcrLanguage,
        includeImages: Boolean,
        continueSessionId: String?
    ): QuerySessionDetail {
        val searchQuery = SearchQuery(query, url, languagePattern, ocrLanguage, includeImages)
        
        // Resolve user's proxy configuration for this URL
        // Custom/Premium proxies are used directly; None triggers adaptive bypass strategy
        val proxyConfig = proxySettingsService.resolveProxyForUrl(userId, url)
        
        // Load prior session context if continueSessionId is provided
        val priorSessionContext = loadPriorSessionContext(continueSessionId)
        
        logger.debug(
            "Executing {} search for query: {} with proxy: {} language pattern: {} OCR language: {} includeImages: {} continueSessionId: {}", 
            mode, query, proxyConfig::class.simpleName, languagePattern, ocrLanguage, includeImages, continueSessionId
        )
        
        val orchestrator = when (mode) {
            SearchMode.LIVE_CRAWLING -> agenticBrowserSearchOrchestrator
            SearchMode.CACHE_ONLY -> cacheOnlySearchOrchestrator
        }
        
        // Collect the flow until we get a terminal event (either success or error)
        val completionDeferred = CompletableDeferred<SearchEvent>()

        val job = applicationScope.scope.launch {
            orchestrator.execute(searchQuery, maxCacheAge, apiKeyId, proxyConfig, priorSessionContext)
                .onEach { event ->
                    // Complete on any terminal event
                    when (event) {
                        is SearchEvent.SessionCompleted -> completionDeferred.complete(event)
                        is SearchEvent.SessionError -> completionDeferred.complete(event)
                        else -> { /* Ignore non-terminal events */ }
                    }
                }
                .first { it is SearchEvent.SessionCompleted || it is SearchEvent.SessionError }
        }

        val terminalEvent = completionDeferred.await()
        job.cancel()
        
        // Handle both success and error cases
        return when (terminalEvent) {
            is SearchEvent.SessionCompleted -> {
                querySessionService.getSessionDetail(terminalEvent.sessionId, userId)
            }
            is SearchEvent.SessionError -> {
                // For errors, still return the session detail (which will contain the error state)
                querySessionService.getSessionDetail(terminalEvent.sessionId, userId)
            }
            else -> throw IllegalStateException("Unexpected terminal event type: ${terminalEvent::class.simpleName}")
        }
    }

    override suspend fun executeStreaming(
        query: String,
        url: String,
        maxCacheAge: Long?,
        mode: SearchMode,
        apiKeyId: ApiKeyId,
        userId: UserId,
        languagePattern: String?,
        ocrLanguage: OcrLanguage,
        includeImages: Boolean,
        continueSessionId: String?
    ): Flow<SearchEvent> {
        val searchQuery = SearchQuery(query, url, languagePattern, ocrLanguage, includeImages)
        
        // Resolve user's proxy configuration for this URL
        val proxyConfig = proxySettingsService.resolveProxyForUrl(userId, url)
        
        // Load prior session context if continueSessionId is provided
        val priorSessionContext = loadPriorSessionContext(continueSessionId)
        
        logger.debug(
            "Starting streaming {} search for query: {} with proxy: {} language pattern: {} OCR language: {} includeImages: {} continueSessionId: {}", 
            mode, query, proxyConfig::class.simpleName, languagePattern, ocrLanguage, includeImages, continueSessionId
        )
        
        val orchestrator = when (mode) {
            SearchMode.LIVE_CRAWLING -> agenticBrowserSearchOrchestrator
            SearchMode.CACHE_ONLY -> cacheOnlySearchOrchestrator
        }
        
        return orchestrator.execute(searchQuery, maxCacheAge, apiKeyId, proxyConfig, priorSessionContext)
    }
    
    /**
     * Load prior session context from a previous session ID.
     * Returns null if the session doesn't exist or has no answer.
     */
    private suspend fun loadPriorSessionContext(continueSessionId: String?): PriorSessionContext? {
        if (continueSessionId == null) return null
        
        return try {
            val priorSession = querySessionService.getSession(QuerySessionId(continueSessionId))
            if (priorSession.answer.isNullOrBlank()) {
                logger.warn("Prior session {} has no answer, skipping context injection", continueSessionId)
                null
            } else {
                logger.info(
                    "Loading prior session context: sessionId={}, query='{}', answerLength={}",
                    continueSessionId, priorSession.query, priorSession.answer!!.length
                )
                PriorSessionContext(
                    sessionId = continueSessionId,
                    query = priorSession.query,
                    answer = priorSession.answer!!
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to load prior session {}: {}", continueSessionId, e.message)
            null
        }
    }
}
