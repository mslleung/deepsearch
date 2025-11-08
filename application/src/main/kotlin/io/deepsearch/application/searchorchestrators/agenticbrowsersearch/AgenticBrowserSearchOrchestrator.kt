package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.application.services.INormalizeUrlService
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.application.services.IUrlContentProcessingService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
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
import io.deepsearch.domain.models.entities.FinishReason
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
import kotlinx.coroutines.channels.Channel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.measureTimeMillis
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    private val dispatchers: IDispatcherProvider
) : IAgenticBrowserSearchOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun execute(searchQuery: SearchQuery, maxUrls: Int?, searchDurationSeconds: Int?): SearchResult =
        withContext(dispatchers.io) {
            val result: SearchResult
            val executionTime = measureTimeMillis {
                result = executeSearchForQuery(searchQuery, maxUrls, searchDurationSeconds)
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
        searchDurationSeconds: Int? = null
    ): SearchResult {
        val session = querySessionService.createSession(searchQuery.query, searchQuery.url)
        val sessionId = session.id
        val budget = SearchBudget(
            timeLimitMs = (searchDurationSeconds ?: 60) * 1000L,
            maxLinks = maxUrls ?: 20
        )

        try {
            logger.debug("[{}] Executing search for query: {}", sessionId, searchQuery.query)

            // Use CompletableDeferred to capture result immediately without waiting for flow cancellation
            val resultDeferred = CompletableDeferred<SearchResult>()

            // Channel for discovered links
            val discoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)

            // Launch flow processing in background
            val flowJob = applicationScope.scope.launch {
                try {
                    merge(
                        processInitialLinkFlow(sessionId, searchQuery, discoveredLinksChannel),
                        processDiscoveredLinksFlow(sessionId, searchQuery, budget, discoveredLinksChannel)
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
                        .takeWhile { answerAccumulator -> isAnswerReady(sessionId, budget, answerAccumulator) }
                        .take(1)
                        .onEach { finalAnswerAccumulator -> // should only be one emission
                            finishQuerySession(sessionId, searchQuery, finalAnswerAccumulator, budget, resultDeferred)
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
        discoveredLinksChannel: Channel<WebpageLink>
    ): Flow<MarkdownResult> {
        return flowOf(searchQuery.url)
            .flatMapMerge { url ->
                val normalizedUrl = normalizeUrlService.normalize(url) ?: url

                urlContentProcessingService.processUrlAsFlow(normalizedUrl, searchQuery.query)
                    .cancellable()
                    .onEach { event ->
                        when (event) {
                            is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                logger.debug(
                                    "[{}] Initial URL discovered {} links",
                                    sessionId,
                                    event.discoveredLinks.size
                                )
                                event.discoveredLinks.forEach { link ->
                                    discoveredLinksChannel.send(link)
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
            }
    }

    /**
     * Process discovered links from Google search, sitemap, and on-page analysis.
     * Network and markdown conversion errors are caught and recorded, allowing other links to continue.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processDiscoveredLinksFlow(
        sessionId: String,
        searchQuery: SearchQuery,
        budget: SearchBudget,
        discoveredLinksChannel: Channel<WebpageLink>
    ): Flow<MarkdownResult> {
        val googleSearchFlow = createGoogleSearchLinkDiscoveryFlow(sessionId, searchQuery)
        val sitemapFlow = createSitemapLinkDiscoveryFlow(sessionId, searchQuery)

        val processedUrls = ConcurrentHashMap.newKeySet<String>()

        return merge(googleSearchFlow, sitemapFlow, discoveredLinksChannel.receiveAsFlow())
            .takeWhile {
                !querySessionService.isBudgetExceeded(
                    sessionId,
                    budget
                )
            } // check the budget instead of processedUrls size, because the link may fail and will not be counted
            .flatMapMerge(concurrency = 100) { link ->
                flow {
                    val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url

                    // Atomic check-and-add: skip if already processing
                    if (!processedUrls.add(normalizedUrl)) {
                        logger.debug("[{}] Skipping already queued URL: {}", sessionId, normalizedUrl)
                        return@flow
                    }

                    urlContentProcessingService.processUrlAsFlow(normalizedUrl, searchQuery.query)
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
                                        "[{}] Failed to process discovered link {}: {} (type: {})",
                                        sessionId,
                                        e.url,
                                        e.message,
                                        e::class.simpleName
                                    )
                                }

                                else -> {
                                    logger.error(
                                        "[{}] Unexpected error processing discovered link {}: {}",
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
                                        "[{}] Links discovered for {}: {} links",
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
                                        "[{}] Markdown extracted for {}: {} chars (cached: {})",
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
                    logger.info("[{}] Discovered link processing cancelled: {}", sessionId, cause.message)
                } else {
                    logger.info("[{}] Discovered link processing complete", sessionId)
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

    private suspend fun isAnswerReady(
        sessionId: String,
        searchBudget: SearchBudget,
        answerAccumulator: AnswerAccumulator
    ): Boolean {
        return answerAccumulator.isComplete
                || querySessionService.isBudgetExceeded(sessionId, searchBudget)
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
     * Result containing only markdown data for downstream processing.
     */
    private data class MarkdownResult(
        val url: String,
        val markdown: String
    )

}


