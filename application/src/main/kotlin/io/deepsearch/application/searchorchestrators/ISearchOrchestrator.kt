package io.deepsearch.application.searchorchestrators

import io.deepsearch.application.services.SearchEvent
import io.deepsearch.domain.agents.PriorSessionContext
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.proxy.ProxyConfiguration
import kotlinx.coroutines.flow.Flow

interface ISearchOrchestrator {
    /**
     * Execute a search query and return a flow of search events.
     * 
     * The flow emits:
     * - SessionCreated: when search starts (includes sessionId)
     * - UrlProcessed: for each URL processed (optional, mainly for live crawling)
     * - SourcesEvaluated: when source evaluation completes (optional)
     * - SessionCompleted: when search finishes with answer
     * - SessionError: if an error occurs
     * 
     * For blocking execution: collect the flow until SessionCompleted/SessionError
     * For streaming: subscribe and process events as they arrive
     * 
     * @param proxyConfig User's proxy configuration. None (default) uses adaptive bypass strategy.
     *                    Custom/Premium proxies are used directly without bypass logic.
     * @param priorSessionContext Optional context from a previous session for continuation.
     *                            When provided, the answer synthesis will avoid repeating
     *                            information already provided in the prior session.
     */
    fun execute(
        searchQuery: SearchQuery, 
        maxCacheAge: Long? = null, 
        apiKeyId: ApiKeyId,
        proxyConfig: ProxyConfiguration = ProxyConfiguration.None,
        priorSessionContext: PriorSessionContext? = null
    ): Flow<SearchEvent>
}