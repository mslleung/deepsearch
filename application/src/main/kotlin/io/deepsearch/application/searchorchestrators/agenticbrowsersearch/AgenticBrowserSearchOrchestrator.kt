package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.domain.services.INormalizeUrlService
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.application.services.IUrlContentProcessingService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.application.services.WebpageCacheService
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.IQueryBreakdownAgent
import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.StreamingAnswerInput
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.UncachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.exceptions.NetworkConnectionException
import io.deepsearch.domain.exceptions.MarkdownConversionException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.measureTimeMillis
import java.util.concurrent.ConcurrentHashMap

interface IAgenticBrowserSearchOrchestrator : ISearchOrchestrator

/**
 * Orchestrates agentic search using a reactive flow-based approach.
 * Discovers links via Google search and on-page analysis, then processes them
 * concurrently using flatMapMerge for maximum throughput. Markdowns are batched
 * and fed to answer generation, which terminates early when complete.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class AgenticBrowserSearchOrchestrator(
    private val applicationScope: IApplicationCoroutineScope,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val normalizeUrlService: INormalizeUrlService,
    private val queryExpansionAgent: IQueryExpansionAgent,
    private val queryBreakdownAgent: IQueryBreakdownAgent,
    private val aggregateSearchResultsAgent: IAggregateSearchResultsAgent,
    private val urlContentProcessingService: IUrlContentProcessingService,
    private val streamingAnswerAgent: IStreamingAnswerAgent,
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService,
    private val webpageCacheService: WebpageCacheService,
    private val dispatchers: IDispatcherProvider
) : IAgenticBrowserSearchOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun execute(searchQuery: SearchQuery, maxUrls: Int?, searchDurationSeconds: Int?, cacheExpiryMs: Long?): SearchResult =
        withContext(dispatchers.io) {
            val result: SearchResult
            val executionTime = measureTimeMillis {
                result = executeSearchForQuery(searchQuery, maxUrls, searchDurationSeconds, cacheExpiryMs)
            }

            logger.info("Execute completed in {} ms for query: {}", executionTime, searchQuery.query)
            result
        }

    /**
     * Execute the full search workflow for a single query using reactive flow composition.
     * Markdowns are extracted as links are discovered, batched, and fed to answer generation.
     * Flow cancellation propagates upstream when answer is complete or budget exceeded.
     */
    private suspend fun executeSearchForQuery(
        searchQuery: SearchQuery,
        maxUrls: Int? = null,
        searchDurationSeconds: Int? = null,
        cacheExpiryMs: Long?
    ): SearchResult {
        val session = querySessionService.createSession(searchQuery.query, searchQuery.url)
        val sessionId = session.id
        try {
            val budget = SearchBudget(
                timeLimitMs = (searchDurationSeconds ?: 60) * 1000L,
                maxLinks = maxUrls ?: 20
            )
            logger.debug("[{}] Executing search for query: {}", sessionId, searchQuery.query)

            // Use CompletableDeferred to capture result immediately without waiting for flow cancellation
            val resultDeferred = CompletableDeferred<SearchResult>()

            // for deduping links
            val seenUrls = ConcurrentHashMap.newKeySet<String>()

            // Channel for discovered links
            val initialDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
            val googleSearchDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
            val serperSearchDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
            val sitemapDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
            val vectorSearchDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
            val recursiveDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)

            // To be removed:
            googleSearchDiscoveredLinksChannel.close()
            sitemapDiscoveredLinksChannel.close()

            // Launch flow processing in background
            val flowJob = applicationScope.scope.launch {
                try {
                    var answerAccumulator = AnswerAccumulator()

                    merge(
                        processInitialLinkFlow(
                            sessionId,
                            searchQuery,
                            seenUrls,
                            initialDiscoveredLinksChannel,
                            cacheExpiryMs
                        ),
//                        processGoogleSearchLinksFlow(
//                            sessionId,
//                            searchQuery,
//                            seenUrls,
//                            budget,
//                            googleSearchDiscoveredLinksChannel,
//                            cacheExpiryMs
//                        ),
                        processSerperSearchLinksFlow(
                            sessionId,
                            searchQuery,
                            seenUrls,
                            budget,
                            serperSearchDiscoveredLinksChannel,
                            cacheExpiryMs
                        ),
//                        processSitemapLinksFlow(
//                            sessionId,
//                            searchQuery,
//                            seenUrls,
//                            budget,
//                            sitemapDiscoveredLinksChannel,
//                            cacheExpiryMs
//                        ),
                        processVectorSearchFlow(
                            sessionId,
                            searchQuery,
                            cacheExpiryMs,
                            vectorSearchDiscoveredLinksChannel
                        ),
                        processRecursiveDiscoveredLinksFlow(
                            sessionId,
                            searchQuery,
                            seenUrls,
                            budget,
                            initialDiscoveredLinksChannel,
                            googleSearchDiscoveredLinksChannel,
                            serperSearchDiscoveredLinksChannel,
                            sitemapDiscoveredLinksChannel,
                            vectorSearchDiscoveredLinksChannel,
                            recursiveDiscoveredLinksChannel,
                            cacheExpiryMs
                        )
                    )
                        .cancellable()
                        .filter { it.markdown.isNotBlank() }
                        .chunked(1)
                        .runningFold(AnswerAccumulator()) { state, markdownResults ->
                            aggregateMarkdownResultIntoAnswer(
                                sessionId,
                                searchQuery,
                                state,
                                markdownResults
                            )
                        }
                        .onEach { answerAccumulator = it }
                        .filter { answerAccumulator -> answerAccumulator.isComplete }
                        .take(1)
                        .onEach { completedAnswerAccumulator -> // should only be one emission
                            finishQuerySession(sessionId, searchQuery, completedAnswerAccumulator, budget, resultDeferred)
                        }
                        .onCompletion { // answer did not complete, but the upstream flow completed, return our incomplete answer
                            finishQuerySession(sessionId, searchQuery, answerAccumulator, budget, resultDeferred)
                        }
                        .single()
                } catch (e: CancellationException) {
                    logger.debug("[{}] Flow cancelled after result obtained", sessionId)
                } catch (e: Exception) {
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.completeExceptionally(e)
                    }
                }
            }

            // Wait for the result with timeout
            val result = withTimeout(budget.timeLimitMs + 5000) {
                resultDeferred.await()
            }

            // Cancel the flow job asynchronously
            flowJob.cancel()

            return result
        } catch (e: Exception) {
            logger.error("[{}] Error in executeSearchForQuery: {}", sessionId, e.message, e)
            querySessionService.hardTimeout(sessionId, e.message ?: "Unknown error")
            throw e
        }
    }

    /**
     * Process the initial user-provided URL.
     * Network and markdown conversion errors are NOT caught - they fail the entire search.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processInitialLinkFlow(
        sessionId: String,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        initialDiscoveredLinksChannel: Channel<WebpageLink>,
        cacheExpiryMs: Long?
    ): Flow<MarkdownResult> {
        return flowOf(searchQuery.url)
            .flatMapMerge { url ->
                val normalizedUrl = normalizeUrlService.normalize(url) ?: url

                urlContentProcessingService.processUrlAsFlow(normalizedUrl, searchQuery.query, cacheExpiryMs)
                    .cancellable()
                    .filter { link ->
                        val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url

                        if (seenUrls.contains(normalizedUrl)) {
                            logger.debug(
                                "processInitialLinkFlow [{}] Skipping already seen URL: {}",
                                sessionId,
                                normalizedUrl
                            )
                            false
                        } else {
                            seenUrls.add(normalizedUrl)
                            true
                        }
                    }
                    .onEach { event ->
                        when (event) {
                            is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                logger.debug(
                                    "[{}] Initial URL discovered {} links",
                                    sessionId,
                                    event.discoveredLinks.size
                                )
                                event.discoveredLinks.forEach { link ->
                                    initialDiscoveredLinksChannel.send(link)
                                }
                                initialDiscoveredLinksChannel.close()
                            }

                            is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                val urlAccess = if (event.wasCached) {
                                    CachedUrlAccess(event.url, Clock.System.now())
                                } else {
                                    UncachedUrlAccess(event.url, Clock.System.now())
                                }
                                urlAccessService.recordUrlAccess(sessionId, urlAccess)

                                logger.debug(
                                    "[{}] Initial URL markdown extracted: {} chars (cached: {})",
                                    sessionId,
                                    event.markdown.length,
                                    event.wasCached
                                )
                            }
                        }
                    }
                    .filterIsInstance<IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete>()
                    .map { event -> MarkdownResult(event.url, event.markdown) }
                    .onCompletion { initialDiscoveredLinksChannel.close() }
            }
    }

    /**
     * Common processing logic for discovered links with budget checking and error handling.
     * Network and markdown conversion errors are caught and recorded, allowing other links to continue.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processDiscoveredLinksFlow(
        sessionId: String,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        budget: SearchBudget,
        linkSource: Flow<WebpageLink>,
        discoveredLinksChannel: Channel<WebpageLink>,
        flowName: String,
        cacheExpiryMs: Long?
    ): Flow<MarkdownResult> {
        return linkSource
            .takeWhile {
                !querySessionService.isBudgetExceeded(
                    sessionId,
                    budget
                )
            }
            .filter { link ->
                val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url

                if (seenUrls.contains(normalizedUrl)) {
                    logger.debug(
                        "{} [{}] Skipping already seen URL: {}",
                        flowName,
                        sessionId,
                        normalizedUrl
                    )
                    false
                } else {
                    seenUrls.add(normalizedUrl)
                    true
                }
            }
            .flatMapMerge(concurrency = 100) { link ->
                flow {
                    val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url
                    urlContentProcessingService.processUrlAsFlow(normalizedUrl, searchQuery.query, cacheExpiryMs)
                        .cancellable()
                        .catch { e ->
                            when (e) {
                                is CancellationException -> {
                                    throw e
                                }

                                is NetworkConnectionException, is MarkdownConversionException -> {
                                    val failedAccess = FailedUrlAccess(
                                        url = e.url,
                                        timestamp = Clock.System.now(),
                                        exceptionType = e::class.simpleName!!,
                                        message = e.message!!
                                    )
                                    urlAccessService.recordUrlAccess(sessionId, failedAccess)

                                    logger.warn(
                                        "{} [{}] Failed to process discovered link {}: {} (type: {})",
                                        flowName,
                                        sessionId,
                                        e.url,
                                        e.message,
                                        e::class.simpleName
                                    )
                                }

                                else -> {
                                    logger.error(
                                        "{} [{}] Unexpected error processing discovered link {}: {}",
                                        flowName,
                                        sessionId,
                                        normalizedUrl,
                                        e.message,
                                        e
                                    )
                                    throw e
                                }
                            }
                        }
                        .onEach { event ->
                            when (event) {
                                is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                    logger.debug(
                                        "{} [{}] Links discovered for {}: {} links",
                                        flowName,
                                        sessionId,
                                        event.url,
                                        event.discoveredLinks.size
                                    )
                                    event.discoveredLinks.forEach { discoveredLink ->
                                        discoveredLinksChannel.send(discoveredLink)
                                    }
                                }

                                is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                    val urlAccess = if (event.wasCached) {
                                        CachedUrlAccess(event.url, Clock.System.now())
                                    } else {
                                        UncachedUrlAccess(event.url, Clock.System.now())
                                    }
                                    urlAccessService.recordUrlAccess(sessionId, urlAccess)

                                    logger.debug(
                                        "{} [{}] Markdown extracted for {}: {} chars (cached: {})",
                                        flowName,
                                        sessionId,
                                        event.url,
                                        event.markdown.length,
                                        event.wasCached
                                    )
                                }
                            }
                        }
                        .filterIsInstance<IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete>()
                        .map { event -> MarkdownResult(event.url, event.markdown) }
                        .collect { emit(it) }
                }
            }
            .onCompletion { cause ->
                if (cause != null) {
                    logger.info(
                        "{} [{}] Discovered link processing cancelled: {}",
                        flowName,
                        sessionId,
                        cause.message
                    )
                } else {
                    logger.info("{} [{}] Discovered link processing complete", flowName, sessionId)
                }
                discoveredLinksChannel.close()
            }
    }

    /**
     * Process discovered links from Google search.
     * Network and markdown conversion errors are caught and recorded, allowing other links to continue.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processGoogleSearchLinksFlow(
        sessionId: String,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        budget: SearchBudget,
        googleSearchDiscoveredLinksChannel: Channel<WebpageLink>,
        cacheExpiryMs: Long?
    ): Flow<MarkdownResult> {
        return processDiscoveredLinksFlow(
            sessionId = sessionId,
            searchQuery = searchQuery,
            seenUrls = seenUrls,
            budget = budget,
            linkSource = createGoogleSearchLinkDiscoveryFlow(sessionId, searchQuery),
            discoveredLinksChannel = googleSearchDiscoveredLinksChannel,
            flowName = "processGoogleSearchLinksFlow",
            cacheExpiryMs = cacheExpiryMs
        )
    }

    /**
     * Process discovered links from SERP search.
     * Network and markdown conversion errors are caught and recorded, allowing other links to continue.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processSerperSearchLinksFlow(
        sessionId: String,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        budget: SearchBudget,
        serperSearchDiscoveredLinksChannel: Channel<WebpageLink>,
        cacheExpiryMs: Long?
    ): Flow<MarkdownResult> {
        return processDiscoveredLinksFlow(
            sessionId = sessionId,
            searchQuery = searchQuery,
            seenUrls = seenUrls,
            budget = budget,
            linkSource = createSerperSearchLinkDiscoveryFlow(sessionId, searchQuery),
            discoveredLinksChannel = serperSearchDiscoveredLinksChannel,
            flowName = "processSerperSearchLinksFlow",
            cacheExpiryMs = cacheExpiryMs
        )
    }

    /**
     * Process discovered links from sitemap.
     * Network and markdown conversion errors are caught and recorded, allowing other links to continue.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processSitemapLinksFlow(
        sessionId: String,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        budget: SearchBudget,
        sitemapDiscoveredLinksChannel: Channel<WebpageLink>,
        cacheExpiryMs: Long?
    ): Flow<MarkdownResult> {
        return processDiscoveredLinksFlow(
            sessionId = sessionId,
            searchQuery = searchQuery,
            seenUrls = seenUrls,
            budget = budget,
            linkSource = createSitemapLinkDiscoveryFlow(sessionId, searchQuery),
            discoveredLinksChannel = sitemapDiscoveredLinksChannel,
            flowName = "processSitemapLinksFlow",
            cacheExpiryMs = cacheExpiryMs
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun processRecursiveDiscoveredLinksFlow(
        sessionId: String,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        budget: SearchBudget,
        initialDiscoveredLinksChannel: Channel<WebpageLink>,
        googleSearchDiscoveredLinksChannel: Channel<WebpageLink>,
        serperSearchDiscoveredLinksChannel: Channel<WebpageLink>,
        sitemapDiscoveredLinksChannel: Channel<WebpageLink>,
        vectorSearchDiscoveredLinksChannel: Channel<WebpageLink>,
        recursiveDiscoveredLinksChannel: Channel<WebpageLink>,
        cacheExpiryMs: Long?
    ): Flow<MarkdownResult> {
        val inFlightLinkDiscoveryProcessing = ConcurrentHashMap.newKeySet<String>()

        return merge(
            merge(
                initialDiscoveredLinksChannel.receiveAsFlow(),
                googleSearchDiscoveredLinksChannel.receiveAsFlow(),
                serperSearchDiscoveredLinksChannel.receiveAsFlow(),
                sitemapDiscoveredLinksChannel.receiveAsFlow(),
                vectorSearchDiscoveredLinksChannel.receiveAsFlow(),
            )
                .onCompletion {
                    // in case none of them emit any discovered links, we will do a check to close the recursive channel properly
                    if (inFlightLinkDiscoveryProcessing.isEmpty()) {
                        recursiveDiscoveredLinksChannel.close()
                    }
                },
            recursiveDiscoveredLinksChannel.receiveAsFlow()
        )
            .takeWhile {
                !querySessionService.isBudgetExceeded(
                    sessionId,
                    budget
                )
            }
            .filter { link ->
                val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url

                if (seenUrls.contains(normalizedUrl)) {
                    logger.debug(
                        "processRecursiveDiscoveredLinksFlow [{}] Skipping already seen URL: {}",
                        sessionId,
                        normalizedUrl
                    )
                    false
                } else {
                    seenUrls.add(normalizedUrl)
                    true
                }
            }
            .flatMapMerge(concurrency = 100) { link ->
                flow {
                    val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url
                    inFlightLinkDiscoveryProcessing.add(normalizedUrl)
                    urlContentProcessingService.processUrlAsFlow(normalizedUrl, searchQuery.query, cacheExpiryMs)
                        .cancellable()
                        .catch { e ->
                            when (e) {
                                is CancellationException -> {
                                    throw e
                                }

                                is NetworkConnectionException, is MarkdownConversionException -> {
                                    val failedAccess = FailedUrlAccess(
                                        url = e.url,
                                        timestamp = Clock.System.now(),
                                        exceptionType = e::class.simpleName!!,
                                        message = e.message!!
                                    )
                                    urlAccessService.recordUrlAccess(sessionId, failedAccess)
                                    inFlightLinkDiscoveryProcessing.remove(e.url)
                                    if (initialDiscoveredLinksChannel.isClosedForSend &&
                                        googleSearchDiscoveredLinksChannel.isClosedForSend &&
                                        serperSearchDiscoveredLinksChannel.isClosedForSend &&
                                        sitemapDiscoveredLinksChannel.isClosedForSend &&
                                        vectorSearchDiscoveredLinksChannel.isClosedForSend &&
                                        inFlightLinkDiscoveryProcessing.isEmpty()
                                    ) {
                                        recursiveDiscoveredLinksChannel.close()
                                    }

                                    logger.warn(
                                        "processRecursiveDiscoveredLinksFlow [{}] Failed to process discovered link {}: {} (type: {})",
                                        sessionId,
                                        e.url,
                                        e.message,
                                        e::class.simpleName
                                    )
                                }

                                else -> {
                                    logger.error(
                                        "processRecursiveDiscoveredLinksFlow [{}] Unexpected error processing discovered link {}: {}",
                                        sessionId,
                                        normalizedUrl,
                                        e.message,
                                        e
                                    )
                                    throw e
                                }
                            }
                        }
                        .onEach { event ->
                            when (event) {
                                is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                    logger.debug(
                                        "processRecursiveDiscoveredLinksFlow [{}] Links discovered for {}: {} links",
                                        sessionId,
                                        event.url,
                                        event.discoveredLinks.size
                                    )
                                    event.discoveredLinks.forEach { discoveredLink ->
                                        recursiveDiscoveredLinksChannel.send(discoveredLink)
                                    }
                                    inFlightLinkDiscoveryProcessing.remove(event.url)
                                    logger.debug(
                                        "processRecursiveDiscoveredLinksFlow {}",
                                        inFlightLinkDiscoveryProcessing
                                    )
                                    if (initialDiscoveredLinksChannel.isClosedForSend &&
                                        googleSearchDiscoveredLinksChannel.isClosedForSend &&
                                        serperSearchDiscoveredLinksChannel.isClosedForSend &&
                                        sitemapDiscoveredLinksChannel.isClosedForSend &&
                                        vectorSearchDiscoveredLinksChannel.isClosedForSend &&
                                        inFlightLinkDiscoveryProcessing.isEmpty()
                                    ) {
                                        recursiveDiscoveredLinksChannel.close()
                                    }
                                }

                                is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                    val urlAccess = if (event.wasCached) {
                                        CachedUrlAccess(event.url, Clock.System.now())
                                    } else {
                                        UncachedUrlAccess(event.url, Clock.System.now())
                                    }
                                    urlAccessService.recordUrlAccess(sessionId, urlAccess)

                                    logger.debug(
                                        "processRecursiveDiscoveredLinksFlow [{}] Markdown extracted for {}: {} chars (cached: {})",
                                        sessionId,
                                        event.url,
                                        event.markdown.length,
                                        event.wasCached
                                    )
                                }
                            }
                        }
                        .filterIsInstance<IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete>()
                        .map { event -> MarkdownResult(event.url, event.markdown) }
                        .collect { emit(it) }
                }
            }
            .onCompletion { cause ->
                if (cause != null) {
                    logger.info(
                        "processRecursiveDiscoveredLinksFlow [{}] Discovered link processing cancelled: {}",
                        sessionId,
                        cause.message
                    )
                } else {
                    logger.info(
                        "processRecursiveDiscoveredLinksFlow [{}] Discovered link processing complete",
                        sessionId
                    )
                }
            }
    }

    /**
     * State accumulated during answer generation from markdown batches.
     */
    private data class AnswerAccumulator(
        val currentAnswer: String?,
        val allUrls: List<String>,
        val allMarkdowns: List<String>,
        val isComplete: Boolean
    ) {
        constructor() : this(null, emptyList(), emptyList(), false)
    }

    /**
     * Flow operator that aggregates markdown batches into a complete answer.
     * Transforms Flow<List<MarkdownResult>> into Flow<SearchResult> that emits once.
     */
    private suspend fun aggregateMarkdownResultIntoAnswer(
        sessionId: String,
        searchQuery: SearchQuery,
        state: AnswerAccumulator,
        markdownResults: List<MarkdownResult>,
    ): AnswerAccumulator {
        val newUrls = state.allUrls + markdownResults.map { it.url }
        val newMarkdowns = state.allMarkdowns + markdownResults.map { it.markdown }

        logger.debug(
            "[{}] Processing batch of {} markdowns (total: {})",
            sessionId,
            markdownResults.size,
            newMarkdowns.size
        )

        val output = streamingAnswerAgent.generate(
            StreamingAnswerInput(
                query = searchQuery.query,
                currentAnswer = state.currentAnswer,
                markdownBatch = markdownResults.map { it.markdown }
            )
        )

        logger.debug(
            "[{}] Answer updated: {} chars, complete: {}",
            sessionId,
            output.updatedAnswer.length,
            output.isComplete
        )

        return AnswerAccumulator(output.updatedAnswer, newUrls, newMarkdowns, output.isComplete)
    }

    private suspend fun finishQuerySession(
        sessionId: String,
        searchQuery: SearchQuery,
        finalAnswerAccumulator: AnswerAccumulator,
        budget: SearchBudget,
        resultDeferred: CompletableDeferred<SearchResult>,
    ) {
        val answer = finalAnswerAccumulator.currentAnswer ?: "No information found"

        if (finalAnswerAccumulator.isComplete) {
            querySessionService.completeSessionAnswerComplete(sessionId, answer)
        } else if (querySessionService.isBudgetExceeded(sessionId, budget)) {
            querySessionService.completeSessionBudgetExceeded(sessionId, answer, budget)
        } else {
            // Flow completed naturally - all links exhausted
            querySessionService.completeSessionLinksExhausted(sessionId, answer)
        }

        val result = SearchResult(
            originalQuery = searchQuery,
            answer = answer,
            content = finalAnswerAccumulator.allMarkdowns.joinToString("\n\n---\n\n"),
            sources = finalAnswerAccumulator.allUrls
        )

        resultDeferred.complete(result)
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
     * SERP search link discovery flow
     */
    private fun createSerperSearchLinkDiscoveryFlow(
        sessionId: String,
        searchQuery: SearchQuery
    ): Flow<WebpageLink> = flow {
        try {
            val serperLinks = webpageLinkDiscoveryService.discoverRelevantLinksBySerper(searchQuery)
            logger.debug("[{}] SERP search discovered {} links", sessionId, serperLinks.size)
            serperLinks.forEach { emit(it) }
        } catch (e: Exception) {
            logger.error("[{}] Failed SERP search: {}", sessionId, e.message, e)
        }
    }

    /**
     * Sitemap link discovery flow
     */
    private fun createSitemapLinkDiscoveryFlow(
        sessionId: String,
        searchQuery: SearchQuery
    ): Flow<WebpageLink> = flow {
        try {
            val sitemapUrl = searchQuery.sitemapUrl
            if (sitemapUrl.isNullOrBlank()) {
                return@flow
            }

            val sitemapLinks = webpageLinkDiscoveryService.discoverSitemapLinks(sitemapUrl)
            logger.debug("[{}] Sitemap discovered {} links", sessionId, sitemapLinks.size)
            sitemapLinks.forEach { emit(it) }
        } catch (e: Exception) {
            logger.error("[{}] Failed sitemap discovery: {}", sessionId, e.message, e)
        }
    }

    /**
     * Process vector similarity search over cached markdowns.
     * Performs semantic search to find relevant cached pages and processes them.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processVectorSearchFlow(
        sessionId: String,
        searchQuery: SearchQuery,
        cacheExpiryMs: Long?,
        vectorSearchDiscoveredLinksChannel: Channel<WebpageLink>
    ): Flow<MarkdownResult> = flow {
        // Search using hybrid search (RRF combining keyword + semantic search)
        val similarWebpages = webpageCacheService.searchHybrid(
            query = searchQuery.query,
            baseUrl = searchQuery.url,
            cacheExpiryMs = cacheExpiryMs,
            limit = 15
        )
        logger.debug("[{}] Hybrid search: Found {} similar webpages", sessionId, similarWebpages.size)

        // Filter to keep only valid webpages with markdown and html
        val validWebpages = similarWebpages.filter { webpage ->
            !webpage.markdown.isNullOrBlank() && !webpage.html.isNullOrBlank()
        }
        logger.debug("[{}] Vector search: {} valid webpages after filtering", sessionId, validWebpages.size)

        // Process similar webpages through the standard flow
        validWebpages.asFlow()
            .flatMapMerge(concurrency = 15) { webpage ->
                flow {
                    try {
                        // Discover relevant links from this cached webpage
                        val discoveredLinks = webpageLinkDiscoveryService.discoverRelevantLinksByAgent(
                            query = searchQuery.query,
                            html = webpage.html!!,
                            url = webpage.url
                        )
                        logger.debug(
                            "[{}] Vector search: Discovered {} links from cached page {}",
                            sessionId,
                            discoveredLinks.size,
                            webpage.url
                        )

                        // Emit discovered links to the channel
                        discoveredLinks.forEach { link ->
                            vectorSearchDiscoveredLinksChannel.send(link)
                        }

                        // Emit the markdown result
                        emit(MarkdownResult(webpage.url, webpage.markdown!!))
                    } catch (e: Exception) {
                        logger.warn(
                            "[{}] Vector search: Failed to process cached webpage {}: {}",
                            sessionId,
                            webpage.url,
                            e.message,
                            e
                        )
                        // Still emit the markdown even if link discovery fails
                        emit(MarkdownResult(webpage.url, webpage.markdown!!))
                    }
                }
            }
            .onCompletion { cause ->
                if (cause != null) {
                    logger.info(
                        "[{}] Vector search processing cancelled: {}",
                        sessionId,
                        cause.message
                    )
                } else {
                    logger.info("[{}] Vector search processing complete", sessionId)
                }
                vectorSearchDiscoveredLinksChannel.close()
            }
            .collect { emit(it) }
    }

    /**
     * Result containing only markdown data for downstream processing.
     */
    private data class MarkdownResult(
        val url: String,
        val markdown: String
    )

}
