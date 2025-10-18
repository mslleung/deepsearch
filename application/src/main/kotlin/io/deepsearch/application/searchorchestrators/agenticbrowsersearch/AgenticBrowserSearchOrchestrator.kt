package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.application.services.INormalizeUrlService
import io.deepsearch.application.services.IRecursiveLinkTraversalService
import io.deepsearch.application.services.IStreamingAnswerGenerationService
import io.deepsearch.application.services.IUrlContentProcessingService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.application.services.UrlProcessingResult
import io.deepsearch.domain.agents.AggregateSearchResultsInput
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.QueryExpansionAgentInput
import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.DispatcherProvider
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IAgenticBrowserSearchOrchestrator : ISearchOrchestrator {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

/**
 * Orchestrates agentic search that recursively discovers and extracts relevant pages in parallel.
 * Uses Google search once on the initial URL, then uses on-page link analysis
 * to recursively traverse the website. Each page is processed in parallel using
 * separate browser contexts for maximum concurrency.
 */
class AgenticBrowserSearchOrchestrator(
    private val browserPool: IBrowserPool,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val normalizeUrlService: INormalizeUrlService,
    private val queryExpansionAgent: IQueryExpansionAgent,
    private val aggregateSearchResultsAgent: IAggregateSearchResultsAgent,
    private val urlContentProcessingService: IUrlContentProcessingService,
    private val recursiveLinkTraversalService: IRecursiveLinkTraversalService,
    private val streamingAnswerGenerationService: IStreamingAnswerGenerationService,
    private val querySessionService: io.deepsearch.application.services.IQuerySessionService,
    private val dispatchers: DispatcherProvider
) : IAgenticBrowserSearchOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Result of Step 1: Google link discovery and initial URL processing.
     */
    private data class Step1Result(
        val googleLinks: List<WebpageLink>,
        val initialResult: UrlProcessingResult
    )

    override suspend fun execute(searchQuery: SearchQuery): SearchResult {
        // Expand the query into multiple sub-queries
        logger.info("Expanding query: {}", searchQuery.query)
        val expansionOutput = queryExpansionAgent.generate(QueryExpansionAgentInput(searchQuery))
        val expandedQueries = expansionOutput.expandedQueries
        
        logger.info("Query expanded into {} sub-queries", expandedQueries.size)
        
        // If only one query, execute directly without aggregation
        if (expandedQueries.size == 1) {
            logger.info("Single query after expansion, executing directly")
            return executeSearchForQuery(expandedQueries[0])
        }
        
        // Execute all expanded queries in parallel
        logger.info("Executing {} queries in parallel", expandedQueries.size)
        val searchResults = withContext(dispatchers.io) {
            expandedQueries.map { query ->
                async {
                    executeSearchForQuery(query)
                }
            }.awaitAll()
        }
        
        // Aggregate results from all sub-queries
        logger.info("Aggregating results from {} sub-queries", searchResults.size)
        val aggregationOutput = aggregateSearchResultsAgent.generate(
            AggregateSearchResultsInput(
                searchQuery = searchQuery,
                searchResults = searchResults
            )
        )
        
        return aggregationOutput.aggregatedResult
    }
    
    /**
     * Execute the full search workflow for a single query with session-based coordination.
     */
    private suspend fun executeSearchForQuery(searchQuery: SearchQuery): SearchResult {
        // Create query session for state coordination
        val session = querySessionService.createSession(searchQuery.query, searchQuery.url)
        val sessionId = session.id
        
        val browser = browserPool.acquireBrowser()
        try {
            logger.debug("[{}] Executing search for query: {}", sessionId, searchQuery.query)
            
            // Transition to initial link discovery
            querySessionService.transitionToInitialDiscovery(sessionId)
            
            // Step 1: Run Google link discovery and processUrl on input URL in parallel
            val step1Result = step1ProcessInitialUrl(searchQuery, browser)

            logger.debug(
                "[{}] Step 1 complete: Extracted {} chars from initial URL, discovered {} Google links + {} on-page links",
                sessionId,
                step1Result.initialResult.markdown.length,
                step1Result.googleLinks.size,
                step1Result.initialResult.discoveredLinks.size
            )

            // Transition to link traversal state
            querySessionService.transitionToLinkTraversal(sessionId)

            // Step 2: Process all discovered links recursively in BACKGROUND (returns Flow)
            // Normalize initial URL for deduplication tracking
            val initialNormalizedUrl = normalizeUrlService.normalize(searchQuery.url) ?: searchQuery.url
            val resultsFlow = recursiveLinkTraversalService.traverseLinksRecursively(
                sessionId = sessionId,
                initialLinks = step1Result.googleLinks + step1Result.initialResult.discoveredLinks,
                processedNormalizedUrls = setOf(initialNormalizedUrl),
                searchQuery = searchQuery,
                browser = browser
            )

            // Step 3: Generate answer in FOREGROUND (returns early when complete, signals background to stop)
            return streamingAnswerGenerationService.generateAnswerStreaming(
                sessionId = sessionId,
                searchQuery = searchQuery,
                initialUrl = step1Result.initialResult.url,
                initialMarkdown = step1Result.initialResult.markdown,
                resultsFlow = resultsFlow
            )
        } catch (e: Exception) {
            logger.error("[{}] Error in executeSearchForQuery: {}", sessionId, e.message, e)
            querySessionService.fail(sessionId, e.message ?: "Unknown error")
            throw e
        } finally {
            browser.close()
        }
    }

    /**
     * Step 1: Run Google link discovery and processUrl on the input URL in parallel.
     * Returns discovered Google links and the result from processing the input URL.
     */
    private suspend fun step1ProcessInitialUrl(
        searchQuery: SearchQuery,
        browser: IBrowser
    ): Step1Result = withContext(dispatchers.io) {
        logger.debug("Step 1: Processing initial URL {} with Google search", searchQuery.url)

        val googleLinksDeferred = async {
            webpageLinkDiscoveryService.discoverRelevantLinksByGoogleSearch(searchQuery)
        }
        val initialProcessingDeferred = async {
            urlContentProcessingService.processUrl(searchQuery.url, searchQuery.query, browser)
        }

        Step1Result(
            googleLinks = googleLinksDeferred.await(),
            initialResult = initialProcessingDeferred.await()
        )
    }
}


