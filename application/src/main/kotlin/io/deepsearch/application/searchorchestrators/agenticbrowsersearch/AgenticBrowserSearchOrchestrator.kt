package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.domain.services.INormalizeUrlService
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.application.services.IUrlContentProcessingService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.application.services.WebpageCacheService
import io.deepsearch.domain.agents.IAnswerReviewerAgent
import io.deepsearch.domain.agents.IAnswerSynthesisAgent
import io.deepsearch.domain.agents.AnswerSynthesisInput
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.IQueryBreakdownAgent
import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.IStreamingSourceShortlistAgent
import io.deepsearch.domain.agents.StreamingSourceShortlistInput
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.UncachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.exceptions.MarkdownConversionException
import io.deepsearch.domain.exceptions.NetworkConnectionException
import io.deepsearch.domain.ext.chunkedWithTimeout
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.SearchMode
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
    private val urlContentProcessingService: IUrlContentProcessingService,
    private val streamingAnswerAgent: IStreamingAnswerAgent,
    private val answerReviewerAgent: IAnswerReviewerAgent,
    private val streamingSourceShortlistAgent: IStreamingSourceShortlistAgent,
    private val answerSynthesisAgent: IAnswerSynthesisAgent,
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService,
    private val webpageCacheService: WebpageCacheService,
    private val dispatchers: IDispatcherProvider,
    private val tokenUsageService: io.deepsearch.application.services.ILlmTokenUsageService
) : IAgenticBrowserSearchOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun execute(
        searchQuery: SearchQuery,
        maxCacheAge: Long?,
        apiKeyId: ApiKeyId
    ): QuerySessionId =
        withContext(dispatchers.io) {
            val sessionId: QuerySessionId
            val executionTime = measureTimeMillis {
                sessionId = executeSearchForQuery(searchQuery, maxCacheAge, apiKeyId)
            }

            logger.info("Execute completed in {} ms for query: {}", executionTime, searchQuery.query)
            sessionId
        }

    /**
     * Execute the full search workflow for a single query using reactive flow composition.
     * Markdowns are extracted as links are discovered, batched, and fed to answer generation.
     * Flow cancellation propagates upstream when answer is complete or budget exceeded.
     * Returns the session ID for the completed search.
     */
    private suspend fun executeSearchForQuery(
        searchQuery: SearchQuery,
        maxCacheAge: Long?,
        apiKeyId: ApiKeyId
    ): QuerySessionId {
        val session = querySessionService.createSession(searchQuery.query, searchQuery.url, apiKeyId, SearchMode.LIVE_CRAWLING)
        val sessionId = session.id
        try {
            val budget = SearchBudget(
                timeLimitMs = 300 * 1000L,
                maxLinks = 100
            )
            logger.debug("[{}] Executing search for query: {}", sessionId.value, searchQuery.query)

            // Use CompletableDeferred to capture completion immediately without waiting for flow cancellation
            val completionDeferred = CompletableDeferred<Unit>()

            // for deduping links
            val seenUrls = ConcurrentHashMap.newKeySet<String>()

            // Channel for discovered links
            val initialDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
            val serperSearchDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
            val hybridSearchDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
            val recursiveDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)

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
                            maxCacheAge
                        ),
                        processSerperSearchLinksFlow(
                            sessionId,
                            searchQuery,
                            seenUrls,
                            budget,
                            serperSearchDiscoveredLinksChannel,
                            maxCacheAge
                        ),
                        processHybridSearchFlow(
                            sessionId,
                            searchQuery,
                            seenUrls,
                            maxCacheAge,
                            hybridSearchDiscoveredLinksChannel
                        ),
                        processRecursiveDiscoveredLinksFlow(
                            sessionId,
                            searchQuery,
                            seenUrls,
                            budget,
                            initialDiscoveredLinksChannel,
                            serperSearchDiscoveredLinksChannel,
                            hybridSearchDiscoveredLinksChannel,
                            recursiveDiscoveredLinksChannel,
                            maxCacheAge
                        )
                    )
                        .cancellable()
                        .filter { it.markdown.isNotBlank() }
                        .chunkedWithTimeout(chunkSize = 5, timeoutMs = 1000)
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
                            finishQuerySession(
                                sessionId,
                                searchQuery,
                                completedAnswerAccumulator,
                                budget,
                                completionDeferred
                            )
                        }
                        .onCompletion { // answer did not complete, but the upstream flow completed, return our incomplete answer
                            if (answerAccumulator.isComplete) {
                                // already called the early exit in the onEach above
                                return@onCompletion
                            }
                            finishQuerySession(sessionId, searchQuery, answerAccumulator, budget, completionDeferred)
                        }
                        .single()
                } catch (e: CancellationException) {
                    logger.debug("[{}] Flow cancelled after result obtained", sessionId.value)
                } catch (e: Exception) {
                    if (!completionDeferred.isCompleted) {
                        completionDeferred.completeExceptionally(e)
                    }
                }
            }

            // Wait for completion with timeout
            withTimeout(budget.timeLimitMs + 60000) {
                completionDeferred.await()
            }

            // Cancel the flow job asynchronously
            flowJob.cancel()

            return sessionId
        } catch (e: Exception) {
            logger.error("[{}] Error in executeSearchForQuery: {}", sessionId.value, e.message, e)
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
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        initialDiscoveredLinksChannel: Channel<WebpageLink>,
        maxCacheAge: Long?
    ): Flow<MarkdownSource> {
        return flowOf(searchQuery.url)
            .flatMapMerge { url ->
                val normalizedUrl = normalizeUrlService.normalize(url) ?: url

                urlContentProcessingService.processUrlAsFlow(normalizedUrl, searchQuery.query, maxCacheAge, sessionId)
                    .filter { event ->
                        val normalizedUrl = normalizeUrlService.normalize(event.url) ?: event.url

                        if (seenUrls.contains(normalizedUrl)) {
                            logger.debug(
                                "processInitialLinkFlow [{}] Skipping already seen URL: {}",
                                sessionId.value,
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
                                    sessionId.value,
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
                                    sessionId.value,
                                    event.markdown.length,
                                    event.wasCached
                                )
                            }
                        }
                    }
                    .filterIsInstance<IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete>()
                    .map { event -> MarkdownSource(event.url, event.title, event.description, event.markdown) }
                    .onCompletion {
                        logger.info("[{}] Initial link processing complete", sessionId.value)
                        initialDiscoveredLinksChannel.close()
                    }
            }
    }

    /**
     * Common processing logic for discovered links with budget checking and error handling.
     * Network and markdown conversion errors are caught and recorded, allowing other links to continue.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processDiscoveredLinksFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        budget: SearchBudget,
        linkSource: Flow<WebpageLink>,
        discoveredLinksChannel: Channel<WebpageLink>,
        flowName: String,
        maxCacheAge: Long?
    ): Flow<MarkdownSource> {
        return linkSource
            .filter { link ->
                val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url

                if (seenUrls.contains(normalizedUrl)) {
                    logger.debug(
                        "{} [{}] Skipping already seen URL: {}",
                        flowName,
                        sessionId.value,
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
                    urlContentProcessingService.processUrlAsFlow(
                        normalizedUrl,
                        searchQuery.query,
                        maxCacheAge,
                        sessionId
                    )
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
                                        message = e.reason
                                    )
                                    urlAccessService.recordUrlAccess(sessionId, failedAccess)

                                    logger.warn(
                                        "{} [{}] Failed to process discovered link {}: {} (type: {})",
                                        flowName,
                                        sessionId.value,
                                        e.url,
                                        e.reason,
                                        e::class.simpleName
                                    )
                                }

                                else -> {
                                    logger.error(
                                        "{} [{}] Unexpected error processing discovered link {}: {}",
                                        flowName,
                                        sessionId.value,
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
                                        sessionId.value,
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
                                        sessionId.value,
                                        event.url,
                                        event.markdown.length,
                                        event.wasCached
                                    )
                                }
                            }
                        }
                        .filterIsInstance<IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete>()
                        .map { event -> MarkdownSource(event.url, event.title, event.description, event.markdown) }
                        .collect { emit(it) }
                }
            }
            .onCompletion { cause ->
                if (cause != null) {
                    logger.info(
                        "{} [{}] Discovered link processing cancelled: {}",
                        flowName,
                        sessionId.value,
                        cause.message
                    )
                } else {
                    logger.info("{} [{}] Discovered link processing complete", flowName, sessionId.value)
                }
                discoveredLinksChannel.close()
            }
    }

    /**
     * Process discovered links from SERP search.
     * Network and markdown conversion errors are caught and recorded, allowing other links to continue.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processSerperSearchLinksFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        budget: SearchBudget,
        serperSearchDiscoveredLinksChannel: Channel<WebpageLink>,
        maxCacheAge: Long?
    ): Flow<MarkdownSource> {
        return processDiscoveredLinksFlow(
            sessionId = sessionId,
            searchQuery = searchQuery,
            seenUrls = seenUrls,
            budget = budget,
            linkSource = createSerperSearchLinkDiscoveryFlow(sessionId, searchQuery),
            discoveredLinksChannel = serperSearchDiscoveredLinksChannel,
            flowName = "processSerperSearchLinksFlow",
            maxCacheAge = maxCacheAge
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun processRecursiveDiscoveredLinksFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        budget: SearchBudget,
        initialDiscoveredLinksChannel: Channel<WebpageLink>,
        serperSearchDiscoveredLinksChannel: Channel<WebpageLink>,
        hybridSearchDiscoveredLinksChannel: Channel<WebpageLink>,
        recursiveDiscoveredLinksChannel: Channel<WebpageLink>,
        maxCacheAge: Long?
    ): Flow<MarkdownSource> {
        val inFlightLinkDiscoveryProcessing = ConcurrentHashMap.newKeySet<String>()

        return merge(
            merge(
                initialDiscoveredLinksChannel.receiveAsFlow(),
                serperSearchDiscoveredLinksChannel.receiveAsFlow(),
                hybridSearchDiscoveredLinksChannel.receiveAsFlow(),
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
                        sessionId.value,
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
                    urlContentProcessingService.processUrlAsFlow(
                        normalizedUrl,
                        searchQuery.query,
                        maxCacheAge,
                        sessionId
                    )
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
                                        message = e.reason
                                    )
                                    urlAccessService.recordUrlAccess(sessionId, failedAccess)
                                    inFlightLinkDiscoveryProcessing.remove(e.url)
                                    if (initialDiscoveredLinksChannel.isClosedForSend &&
                                        serperSearchDiscoveredLinksChannel.isClosedForSend &&
                                        hybridSearchDiscoveredLinksChannel.isClosedForSend &&
                                        inFlightLinkDiscoveryProcessing.isEmpty()
                                    ) {
                                        recursiveDiscoveredLinksChannel.close()
                                    }

                                    logger.warn(
                                        "processRecursiveDiscoveredLinksFlow [{}] Failed to process discovered link {}: {} (type: {})",
                                        sessionId.value,
                                        e.url,
                                        e.reason,
                                        e::class.simpleName
                                    )
                                }

                                else -> {
                                    logger.error(
                                        "processRecursiveDiscoveredLinksFlow [{}] Unexpected error processing discovered link {}: {}",
                                        sessionId.value,
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
                                        sessionId.value,
                                        event.url,
                                        event.discoveredLinks.size
                                    )
                                    inFlightLinkDiscoveryProcessing.remove(event.url)

                                    val newDiscoveredLinks = event.discoveredLinks.filter {
                                        val normalizedDiscoveredLink = normalizeUrlService.normalize(it.url) ?: it.url
                                        !seenUrls.contains(normalizedDiscoveredLink)
                                    }
                                    newDiscoveredLinks.forEach { discoveredLink ->
                                        recursiveDiscoveredLinksChannel.send(discoveredLink)
                                    }
                                    logger.debug(
                                        "processRecursiveDiscoveredLinksFlow in-flight links count: {}",
                                        inFlightLinkDiscoveryProcessing.count()
                                    )
                                    if (initialDiscoveredLinksChannel.isClosedForSend &&
                                        serperSearchDiscoveredLinksChannel.isClosedForSend &&
                                        hybridSearchDiscoveredLinksChannel.isClosedForSend &&
                                        // if there are no in-flight links being processed (where we may discover more links)
                                        // and we are not emitting any new links
                                        // we can be sure that the recursive flow has stalled, so we can close it
                                        inFlightLinkDiscoveryProcessing.isEmpty() &&
                                        newDiscoveredLinks.isEmpty()
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
                                        sessionId.value,
                                        event.url,
                                        event.markdown.length,
                                        event.wasCached
                                    )
                                }
                            }
                        }
                        .filterIsInstance<IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete>()
                        .map { event -> MarkdownSource(event.url, event.title, event.description, event.markdown) }
                        .collect { emit(it) }
                }
            }
            .onCompletion { cause ->
                if (cause != null) {
                    logger.info(
                        "processRecursiveDiscoveredLinksFlow [{}] Discovered link processing cancelled: {}",
                        sessionId.value,
                        cause.message
                    )
                } else {
                    logger.info(
                        "processRecursiveDiscoveredLinksFlow [{}] Discovered link processing complete",
                        sessionId.value
                    )
                }
            }
    }

    /**
     * State accumulated during answer generation from markdown batches.
     */
    private data class AnswerAccumulator(
        val currentShortlist: List<ShortlistedSource>,
        val allMarkdownSources: List<MarkdownSource>,
        val isComplete: Boolean
    ) {
        constructor() : this(emptyList(), emptyList(), false)
    }

    /**
     * Flow operator that aggregates markdown batches into a complete answer.
     * Uses two-stage approach: first curates shortlist, then synthesizes answer when ready.
     */
    private suspend fun aggregateMarkdownResultIntoAnswer(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        state: AnswerAccumulator,
        markdownSources: List<MarkdownSource>,
    ): AnswerAccumulator {
        val newMarkdownSources = state.allMarkdownSources + markdownSources

        logger.debug(
            "[{}] Processing batch of {} markdowns (total: {})",
            sessionId.value,
            markdownSources.size,
            newMarkdownSources.size
        )

        // Update source shortlist
        val shortlistOutput = streamingSourceShortlistAgent.generate(
            StreamingSourceShortlistInput(
                query = searchQuery.query,
                currentShortlist = state.currentShortlist,
                newMarkdownBatch = markdownSources
            )
        )

        // Record token usage for shortlist agent
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "StreamingSourceShortlistAgent",
            modelName = shortlistOutput.tokenUsage.modelName,
            promptTokens = shortlistOutput.tokenUsage.promptTokens,
            outputTokens = shortlistOutput.tokenUsage.outputTokens,
            totalTokens = shortlistOutput.tokenUsage.totalTokens
        )

        logger.debug(
            "[{}] Shortlist updated: {} sources, isGoodEnough: {}, reason: {}",
            sessionId.value,
            shortlistOutput.updatedShortlist.size,
            shortlistOutput.isGoodEnough,
            shortlistOutput.reason
        )

        return AnswerAccumulator(
            currentShortlist = shortlistOutput.updatedShortlist,
            allMarkdownSources = newMarkdownSources,
            isComplete = shortlistOutput.isGoodEnough
        )
    }

    private suspend fun finishQuerySession(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        finalAnswerAccumulator: AnswerAccumulator,
        budget: SearchBudget,
        completionDeferred: CompletableDeferred<Unit>,
    ) {
        logger.info(
            "[{}] Flow ending, synthesizing from {} shortlisted sources",
            sessionId.value,
            finalAnswerAccumulator.currentShortlist.size
        )

        val synthesisOutput = answerSynthesisAgent.generate(
            AnswerSynthesisInput(
                query = searchQuery.query,
                shortlistedSources = finalAnswerAccumulator.currentShortlist
            )
        )

        // Record token usage for synthesis agent
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "AnswerSynthesisAgent",
            modelName = synthesisOutput.tokenUsage.modelName,
            promptTokens = synthesisOutput.tokenUsage.promptTokens,
            outputTokens = synthesisOutput.tokenUsage.outputTokens,
            totalTokens = synthesisOutput.tokenUsage.totalTokens
        )

        val finalAnswerText = synthesisOutput.answer

        // Extract answer sources from shortlist
        val answerSources = finalAnswerAccumulator.currentShortlist.map { it.url }

        // Mark answer sources as used in answer
        if (answerSources.isNotEmpty()) {
            urlAccessService.markUrlsAsUsedInAnswer(sessionId, answerSources)
        }

        // Complete session with appropriate finish reason
        if (finalAnswerAccumulator.isComplete) {
            querySessionService.completeSessionAnswerComplete(sessionId, finalAnswerText)
        } else if (querySessionService.isBudgetExceeded(sessionId, budget)) {
            querySessionService.completeSessionBudgetExceeded(sessionId, finalAnswerText, budget)
        } else {
            // Flow completed naturally - all links exhausted
            querySessionService.completeSessionLinksExhausted(sessionId, finalAnswerText)
        }

        completionDeferred.complete(Unit)
    }

    /**
     * SERP search link discovery flow
     */
    private fun createSerperSearchLinkDiscoveryFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery
    ): Flow<WebpageLink> = flow {
        try {
            val serperLinks = webpageLinkDiscoveryService.discoverRelevantLinksBySerper(searchQuery)
            logger.debug("[{}] SERP search discovered {} links", sessionId.value, serperLinks.size)
            serperLinks.forEach { emit(it) }
        } catch (e: Exception) {
            logger.error("[{}] Failed SERP search: {}", sessionId.value, e.message, e)
        }
    }

    /**
     * Process hybrid search over cached markdowns.
     * Performs hybrid search to find relevant cached pages and processes them.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processHybridSearchFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        maxCacheAge: Long?,
        hybridSearchDiscoveredLinksChannel: Channel<WebpageLink>
    ): Flow<MarkdownSource> = flow {
        // Search using hybrid search (RRF combining keyword + semantic search)
        val similarWebpages = webpageCacheService.searchHybrid(
            query = searchQuery.query,
            baseUrl = searchQuery.url,
            maxCacheAge = maxCacheAge,
            limit = 15,
            sessionId = sessionId
        )
        logger.debug("[{}] Hybrid search: Found {} similar webpages", sessionId.value, similarWebpages.size)

        // Filter to keep only valid webpages with markdown and html
        val validWebpages = similarWebpages.filter { webpage ->
            !webpage.markdown.isNullOrBlank() && !webpage.html.isNullOrBlank()
        }
        logger.debug("[{}] Hybrid search: {} valid webpages after filtering", sessionId.value, validWebpages.size)

        seenUrls.addAll(validWebpages.map { it.url })

        // Record URL access for cached entries
        validWebpages.forEach { webpage ->
            val cachedAccess = CachedUrlAccess(webpage.url, Clock.System.now())
            urlAccessService.recordUrlAccess(sessionId, cachedAccess)
        }

        // immediately emit the valid webpages found from hybrid search
        validWebpages.forEach { emit(MarkdownSource(it.url, it.title, it.description, it.markdown!!)) }

        // Process similar webpages through the standard flow
        validWebpages.asFlow()
            .flatMapMerge(concurrency = 15) { webpage ->
                flow<MarkdownSource> {
                    try {
                        // Discover relevant links from this cached webpage
                        val discoveredLinks = webpageLinkDiscoveryService.discoverRelevantLinksByAgent(
                            query = searchQuery.query,
                            html = webpage.html!!,
                            url = webpage.url,
                            sessionId = sessionId
                        )
                        logger.debug(
                            "[{}] Hybrid search: Discovered {} links from cached page {}",
                            sessionId.value,
                            discoveredLinks.size,
                            webpage.url
                        )

                        // Emit discovered links to the channel
                        discoveredLinks.forEach { link ->
                            hybridSearchDiscoveredLinksChannel.send(link)
                        }
                    } catch (e: Exception) {
                        logger.warn(
                            "[{}] Hybrid search: Failed to process cached webpage {}: {}",
                            sessionId.value,
                            webpage.url,
                            e.message,
                            e
                        )
                    }
                }
            }
            .onCompletion { cause ->
                if (cause != null) {
                    logger.info(
                        "[{}] Hybrid search processing cancelled: {}",
                        sessionId.value,
                        cause.message
                    )
                } else {
                    logger.info("[{}] Hybrid search processing complete", sessionId.value)
                }
                hybridSearchDiscoveredLinksChannel.close()
            }
            .collect {}
    }
}
