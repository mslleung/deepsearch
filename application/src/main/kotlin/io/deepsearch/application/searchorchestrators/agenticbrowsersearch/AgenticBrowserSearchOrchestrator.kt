package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.application.services.INormalizeUrlService
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlContentProcessingService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.domain.agents.AggregateSearchResultsInput
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.QueryExpansionAgentInput
import io.deepsearch.domain.agents.StreamingAnswerInput
import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.config.IApplicationCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.measureTimeMillis

interface IAgenticBrowserSearchOrchestrator : ISearchOrchestrator {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

/**
 * Orchestrates agentic search using a reactive flow-based approach.
 * Discovers links via Google search and on-page analysis, then processes them
 * concurrently using flatMapMerge for maximum throughput. Markdowns are batched
 * and fed to answer generation, which terminates early when complete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgenticBrowserSearchOrchestrator(
    private val applicationScope: IApplicationCoroutineScope,
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

    override suspend fun execute(searchQuery: SearchQuery): SearchResult = withContext(dispatchers.io) {
        // Expand the query into multiple sub-queries
        logger.info("Expanding query: {}", searchQuery.query)
        val expansionOutput = queryExpansionAgent.generate(QueryExpansionAgentInput(searchQuery))
        val expandedQueries = expansionOutput.expandedQueries

        logger.info("Query expanded into {} sub-queries", expandedQueries.size)

        // If only one query, execute directly without aggregation
        if (expandedQueries.size == 1) {
            logger.info("Single query after expansion, executing directly")
            return@withContext executeSearchForQuery(expandedQueries[0])
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

        aggregationOutput.aggregatedResult
    }

    /**
     * Execute the full search workflow for a single query using reactive flow composition.
     * Markdowns are extracted as links are discovered, batched, and fed to answer generation.
     * Flow cancellation propagates upstream when answer is complete or budget exceeded.
     */
    private suspend fun executeSearchForQuery(searchQuery: SearchQuery): SearchResult {
        val session = querySessionService.createSession(searchQuery.query, searchQuery.url)
        val sessionId = session.id
        val budget = SearchBudget()

        try {
            logger.debug("[{}] Executing search for query: {}", sessionId, searchQuery.query)
            querySessionService.transitionToLinkTraversal(sessionId)

            // Use CompletableDeferred to capture result immediately without waiting for flow cancellation
            val resultDeferred = CompletableDeferred<SearchResult>()

            // Launch flow processing in background
            val flowJob = applicationScope.scope.launch {
                try {
                    extractRelevantMarkdowns(
                        sessionId = sessionId,
                        searchQuery = searchQuery,
                        budget = budget
                    )
                        .cancellable()  // Ensure flow respects cancellation
                        .filter { it.markdown.isNotBlank() }
                        .chunkFirstThenBySize(1, 3)
                        .generateAnswerFromBatches(sessionId, searchQuery, resultDeferred)
                        .collect()  // Collect to completion (or until cancelled)
                } catch (e: CancellationException) {
                    // Normal cancellation after result is found
                    logger.debug("[{}] Flow cancelled after result obtained", sessionId)
                } catch (e: Exception) {
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.completeExceptionally(e)
                    }
                }
            }

            // Wait for the result (not for cancellation)
            val result = resultDeferred.await()

            // Cancel the flow job asynchronously (don't wait)
            flowJob.cancel()

            return result
        } catch (e: Exception) {
            logger.error("[{}] Error in executeSearchForQuery: {}", sessionId, e.message, e)
            querySessionService.fail(sessionId, e.message ?: "Unknown error")
            throw e
        }
    }

    /**
     * Reactive flow that produces markdown results as links are discovered.
     * Cancellation propagates through all merged flows automatically.
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
        budget: SearchBudget
    ): Flow<MarkdownResult> {
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

        val googleSearchLinksFlow = createGoogleSearchLinkDiscoveryFlow(sessionId, searchQuery)
        val sitemapLinksFlow = createSitemapLinkDiscoveryFlow(sessionId, searchQuery)

        return merge(initialLinkFlow, googleSearchLinksFlow, sitemapLinksFlow, discoveredLinksFlow.asSharedFlow())
            .processLinksToMarkdown(
                sessionId = sessionId,
                searchQuery = searchQuery,
                budget = budget,
                visitedUrls = visitedUrls,
                discoveredLinksFlow = discoveredLinksFlow
            )
            .onCompletion { cause ->
                if (cause != null) {
                    logger.info("[{}] Link discovery cancelled: {}", sessionId, cause.message)
                } else {
                    logger.info("[{}] Link discovery complete", sessionId)
                }
            }
    }

    /**
     * Flow A: Google search link discovery
     */
    private fun createGoogleSearchLinkDiscoveryFlow(
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
     * Sitemap link discovery flow
     */
    private fun createSitemapLinkDiscoveryFlow(
        sessionId: String,
        searchQuery: SearchQuery
    ): Flow<WebpageLink> = flow {
        val sitemapUrl = searchQuery.sitemapUrl ?: return@flow
        try {
            val sitemapLinks = webpageLinkDiscoveryService.discoverSitemapLinks(sitemapUrl)
            logger.debug("[{}] Sitemap discovered {} links", sessionId, sitemapLinks.size)
            sitemapLinks.forEach { emit(it) }
        } catch (e: Exception) {
            logger.error("[{}] Failed sitemap discovery: {}", sessionId, e.message, e)
        }
    }

    /**
     * Flow C: Link processing with markdown conversion and discovered link feedback (Flow B).
     * Budget checks cause flow termination which propagates to all merged flows.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Flow<WebpageLink>.processLinksToMarkdown(
        sessionId: String,
        searchQuery: SearchQuery,
        budget: SearchBudget,
        visitedUrls: MutableSet<String>,
        discoveredLinksFlow: MutableSharedFlow<WebpageLink>
    ): Flow<MarkdownResult> =
        // Check budget before accepting each link
        transformWhile { link ->
            if (isBudgetExceeded(sessionId, budget)) {
                logger.info("[{}] Budget exceeded, terminating link processing", sessionId)
                false  // Stop accepting links
            } else {
                emit(link)
                true  // Continue
            }
        }
            .flatMapMerge(concurrency = 100) { link ->
                flow {
                    try {
                        val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url

                        if (shouldSkipUrl(sessionId, normalizedUrl, visitedUrls)) {
                            return@flow
                        }

                        // Process URL and collect events as they're emitted
                        urlContentProcessingService.processUrlAsFlow(normalizedUrl, searchQuery.query)
                            .cancellable()
                            .collect { event ->
                                when (event) {
                                    is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                        // Emit discovered links immediately (~5 seconds)
                                        logger.debug(
                                            "[{}] Links discovered for {}: {} links",
                                            sessionId,
                                            event.url,
                                            event.discoveredLinks.size
                                        )
                                        event.discoveredLinks.forEach { discoveredLink ->
                                            discoveredLinksFlow.emit(discoveredLink)
                                        }
                                    }

                                    is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                        // Emit markdown for batching (~1 minute)
                                        logger.debug(
                                            "[{}] Markdown extracted for {}: {} chars",
                                            sessionId,
                                            event.url,
                                            event.markdown.length
                                        )
                                        emit(MarkdownResult(event.url, event.markdown))
                                    }
                                }
                            }
                    } catch (e: Exception) {
                        // CancellationException (including AbortFlowException) is a normal flow control mechanism
                        // and should be propagated without logging as an error
                        if (e is CancellationException) {
                            throw e
                        }

                        logger.error(
                            "[{}] Failed to process {}: {} : {}",
                            sessionId,
                            link.url,
                            e.message,
                            e.stackTraceToString()
                        )
                    }
                }
            }
            .onEach {
                querySessionService.addTraversedUrl(sessionId, it.url)
            }

    /**
     * Result containing only markdown data for downstream processing.
     */
    private data class MarkdownResult(
        val url: String,
        val markdown: String
    )

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

    /**
     * Generate answer from batches of markdowns, terminating early when complete.
     * When this flow completes, the single() operator cancels all upstream flows.
     */
    private fun Flow<List<MarkdownResult>>.generateAnswerFromBatches(
        sessionId: String,
        searchQuery: SearchQuery,
        resultDeferred: CompletableDeferred<SearchResult>
    ): Flow<SearchResult> = flow {
        var currentAnswer: String? = null
        val allUrls = mutableListOf<String>()
        val allMarkdowns = mutableListOf<String>()

        try {
            transformWhile { batch ->
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

                // If answer is complete, emit result and stop (single() will cancel upstream)
                if (output.isComplete) {
                    logger.info("[{}] Answer complete after {} pages", sessionId, allUrls.size)
                    // Extract to local variable to avoid smart cast issues with captured variable
                    val answer: String = currentAnswer
                    querySessionService.completeSessionWithAnswer(
                        sessionId,
                        answer,
                        allUrls,
                        FinishReason.ANSWER_COMPLETE
                    )

                    val result = SearchResult(
                        originalQuery = searchQuery,
                        answer = answer,
                        content = allMarkdowns.joinToString("\n\n---\n\n"),
                        sources = allUrls
                    )
                    
                    resultDeferred.complete(result)  // Signal immediately
                    emit(result)
                    false  // Stop processing more batches
                } else {
                    true  // Continue processing
                }
            }.collect()

            // This code only runs if transformWhile completed without emitting (LINKS_EXHAUSTED)
            logger.info("[{}] Links exhausted: {} pages total", sessionId, allUrls.size)
            val answer = currentAnswer ?: "No information found"
            querySessionService.completeSessionWithAnswer(
                sessionId,
                answer,
                allUrls,
                FinishReason.LINKS_EXHAUSTED
            )

            val result = SearchResult(
                originalQuery = searchQuery,
                answer = answer,
                content = allMarkdowns.joinToString("\n\n---\n\n"),
                sources = allUrls
            )
            
            resultDeferred.complete(result)  // Signal immediately
            emit(result)
        } catch (e: Exception) {
            logger.error("[{}] Error during answer generation: {}", sessionId, e.message, e)
            throw e
        }
    }

    /**
     * Custom chunking operator that emits the first N items as a single chunk,
     * then chunks subsequent items by a different size.
     *
     * @param firstChunkSize Number of items in the first chunk
     * @param subsequentChunkSize Number of items in subsequent chunks
     */
    private fun <T> Flow<T>.chunkFirstThenBySize(firstChunkSize: Int, subsequentChunkSize: Int): Flow<List<T>> = flow {
        val buffer = mutableListOf<T>()
        var isFirstChunkEmitted = false

        collect { item ->
            buffer.add(item)

            if (!isFirstChunkEmitted && buffer.size >= firstChunkSize) {
                emit(buffer.take(firstChunkSize))
                buffer.subList(0, firstChunkSize).clear()
                isFirstChunkEmitted = true
            } else if (isFirstChunkEmitted && buffer.size >= subsequentChunkSize) {
                emit(buffer.take(subsequentChunkSize))
                buffer.subList(0, subsequentChunkSize).clear()
            }
        }

        // Emit remaining items if any
        if (buffer.isNotEmpty()) {
            emit(buffer.toList())
        }
    }
}


