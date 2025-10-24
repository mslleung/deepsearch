package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.application.services.INormalizeUrlService
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlContentProcessingService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.application.services.IUrlContentProcessingService.UrlProcessingResult
import io.deepsearch.domain.agents.AggregateSearchResultsInput
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.QueryExpansionAgentInput
import io.deepsearch.domain.agents.StreamingAnswerInput
import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.domain.models.entities.FinishReason
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

interface IAgenticBrowserSearchOrchestrator : ISearchOrchestrator {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

/**
 * Orchestrates agentic search using a reactive flow-based approach.
 * Discovers links via Google search and on-page analysis, then processes them
 * concurrently using flatMapMerge for maximum throughput. Markdowns are batched
 * and fed to answer generation, which terminates early when complete.
 */
class AgenticBrowserSearchOrchestrator(
    private val browserPool: IBrowserPool,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val normalizeUrlService: INormalizeUrlService,
    private val queryExpansionAgent: IQueryExpansionAgent,
    private val aggregateSearchResultsAgent: IAggregateSearchResultsAgent,
    private val urlContentProcessingService: IUrlContentProcessingService,
    private val streamingAnswerAgent: IStreamingAnswerAgent,
    private val querySessionService: IQuerySessionService,
    private val dispatchers: IDispatcherProvider
) : IAgenticBrowserSearchOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

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
     * Execute the full search workflow for a single query using reactive flow composition.
     * Markdowns are extracted as links are discovered, batched, and fed to answer generation.
     */
    private suspend fun executeSearchForQuery(searchQuery: SearchQuery): SearchResult {
        val session = querySessionService.createSession(searchQuery.query, searchQuery.url)
        val sessionId = session.id
        val budget = SearchBudget()

        val browser = browserPool.acquireBrowser()
        try {
            logger.debug("[{}] Executing search for query: {}", sessionId, searchQuery.query)
            querySessionService.transitionToLinkTraversal(sessionId)

            // Reactive flow: extract markdowns (including initial) → batch → generate answer
            return extractRelevantMarkdowns(
                sessionId = sessionId,
                searchQuery = searchQuery,
                browser = browser,
                budget = budget
            )
                .batchMarkdowns(20)
                .generateAnswerFromBatches(sessionId, searchQuery)
                .single()
        } catch (e: Exception) {
            logger.error("[{}] Error in executeSearchForQuery: {}", sessionId, e.message, e)
            querySessionService.fail(sessionId, e.message ?: "Unknown error")
            throw e
        } finally {
            browser.close()
        }
    }

    /**
     * Reactive flow that produces markdown results as links are discovered.
     *
     * This function orchestrates parallel link discovery and processing using flow composition:
     * 1. Initial URL flow (seed)
     * 2. Google search flow (Flow A)
     * 3. Discovered links feedback flow (Flow B)
     * 4. All flows merge and process concurrently (Flow C)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun extractRelevantMarkdowns(
        sessionId: String,
        searchQuery: SearchQuery,
        browser: IBrowser,
        budget: SearchBudget
    ): Flow<UrlProcessingResult> {
        val visitedUrls = ConcurrentHashMap.newKeySet<String>()
        val discoveredLinksFlow = MutableSharedFlow<WebpageLink>(
            extraBufferCapacity = Int.MAX_VALUE
        )

        val initialLinkFlow = flowOf(
            WebpageLink(
                url = searchQuery.url,
                source = LinkSource.LINK_RELEVANCE,
                reason = "Initial URL"
            )
        )

        val googleSearchFlow = createGoogleSearchLinkFlow(sessionId, searchQuery)

        return merge(initialLinkFlow, googleSearchFlow, discoveredLinksFlow)
            .processLinksToMarkdown(
                sessionId = sessionId,
                searchQuery = searchQuery,
                browser = browser,
                budget = budget,
                visitedUrls = visitedUrls,
                discoveredLinksFlow = discoveredLinksFlow
            )
            .onCompletion {
                logger.info("[{}] Link discovery complete", sessionId)
            }
    }

    /**
     * Flow A: Google search link discovery
     */
    private fun createGoogleSearchLinkFlow(
        sessionId: String,
        searchQuery: SearchQuery
    ): Flow<WebpageLink> = flow {
        try {
            val googleLinks = webpageLinkDiscoveryService.discoverRelevantLinksByGoogleSearch(searchQuery)
            logger.debug("[{}] Google search discovered {} links", sessionId, googleLinks.size)
            googleLinks.forEach { emit(it) }
        } catch (e: Exception) {
            logger.error("[{}] Failed Google search: {}", sessionId, e.message, e)
        }
    }

    /**
     * Flow C: Link processing with markdown conversion and discovered link feedback (Flow B)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Flow<WebpageLink>.processLinksToMarkdown(
        sessionId: String,
        searchQuery: SearchQuery,
        browser: IBrowser,
        budget: SearchBudget,
        visitedUrls: MutableSet<String>,
        discoveredLinksFlow: MutableSharedFlow<WebpageLink>
    ): Flow<UrlProcessingResult> = flatMapMerge(concurrency = 10) { link ->
        flow {
            try {
                val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url

                if (shouldSkipUrl(sessionId, normalizedUrl, visitedUrls)) {
                    return@flow
                }

                if (isBudgetExceeded(sessionId, budget)) {
                    return@flow
                }

                val result = urlContentProcessingService.processUrl(link.url, searchQuery.query, browser)
                querySessionService.addTraversedUrl(sessionId, normalizedUrl)

                emit(result)

                // Feed discovered links back into the merged flow (Flow B)
                emitDiscoveredLinks(result, discoveredLinksFlow)

                logger.debug(
                    "[{}] Processed {}: {} chars, {} new links discovered",
                    sessionId,
                    normalizedUrl,
                    result.markdown.length,
                    result.discoveredLinks.size
                )
            } catch (e: Exception) {
                logger.warn("[{}] Failed to process {}: {}", sessionId, link.url, e.message)
            }
        }
    }

    private fun shouldSkipUrl(
        sessionId: String,
        normalizedUrl: String,
        visitedUrls: MutableSet<String>
    ): Boolean {
        if (!visitedUrls.add(normalizedUrl)) {
            logger.debug("[{}] Skipping already visited URL: {}", sessionId, normalizedUrl)
            return true
        }
        return false
    }

    private suspend fun isBudgetExceeded(sessionId: String, budget: SearchBudget): Boolean {
        return querySessionService.checkBudgetAndMarkIfExceeded(sessionId, budget)
    }

    private suspend fun emitDiscoveredLinks(
        result: UrlProcessingResult,
        discoveredLinksFlow: MutableSharedFlow<WebpageLink>
    ) {
        result.discoveredLinks.forEach { discoveredLink ->
            discoveredLinksFlow.emit(discoveredLink)
        }
    }

    /**
     * Batch markdowns into groups of BATCH_SIZE.
     */
    private fun Flow<UrlProcessingResult>.batchMarkdowns(
        batchSize: Int
    ): Flow<List<UrlProcessingResult>> = flow {
        val batch = mutableListOf<UrlProcessingResult>()

        collect { result ->
            if (result.markdown.isNotBlank()) {
                batch.add(result)

                if (batch.size >= batchSize) {
                    emit(batch.toList())
                    batch.clear()
                }
            }
        }

        // Emit remaining batch
        if (batch.isNotEmpty()) {
            emit(batch.toList())
        }
    }

    /**
     * Generate answer from batches of markdowns, terminating early when complete.
     */
    private fun Flow<List<UrlProcessingResult>>.generateAnswerFromBatches(
        sessionId: String,
        searchQuery: SearchQuery
    ): Flow<SearchResult> = flow {
        var currentAnswer: String? = null
        val allUrls = mutableListOf<String>()
        val allMarkdowns = mutableListOf<String>()

        try {
            collect { batch ->
                // Track URLs and markdowns
                batch.forEach { result ->
                    allUrls.add(result.url)
                    allMarkdowns.add(result.markdown)
                }

                logger.debug(
                    "[{}] Processing batch of {} markdowns (total: {})",
                    sessionId,
                    batch.size,
                    allMarkdowns.size
                )

                // Generate/update answer
                val output = streamingAnswerAgent.generate(
                    StreamingAnswerInput(
                        query = searchQuery.query,
                        currentAnswer = currentAnswer,
                        markdownBatch = batch.map { it.markdown }
                    )
                )

                currentAnswer = output.updatedAnswer
                logger.debug(
                    "[{}] Answer updated: {} chars, complete: {}",
                    sessionId,
                    output.updatedAnswer.length,
                    output.isComplete
                )

                // If answer is complete, terminate flow immediately
                if (output.isComplete) {
                    logger.info("[{}] Answer complete after {} pages", sessionId, allUrls.size)
                    val answer: String = currentAnswer
                    querySessionService.completeSessionWithAnswer(
                        sessionId,
                        answer,
                        allUrls,
                        FinishReason.ANSWER_COMPLETE
                    )

                    emit(
                        SearchResult(
                            originalQuery = searchQuery,
                            answer = answer,
                            content = allMarkdowns.joinToString("\n\n---\n\n"),
                            sources = allUrls
                        )
                    )
                    return@collect
                }
            }

            // Links exhausted without complete answer
            logger.info("[{}] Links exhausted: {} pages total", sessionId, allUrls.size)
            val answer = currentAnswer ?: "No information found"
            querySessionService.completeSessionWithAnswer(
                sessionId,
                answer,
                allUrls,
                FinishReason.LINKS_EXHAUSTED
            )

            emit(
                SearchResult(
                    originalQuery = searchQuery,
                    answer = answer,
                    content = allMarkdowns.joinToString("\n\n---\n\n"),
                    sources = allUrls
                )
            )
        } catch (e: Exception) {
            logger.error("[{}] Error during answer generation: {}", sessionId, e.message, e)
            throw e
        }
    }
}


