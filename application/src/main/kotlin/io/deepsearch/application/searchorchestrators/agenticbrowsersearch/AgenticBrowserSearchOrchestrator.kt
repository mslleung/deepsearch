package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.ISearchFlowEventService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.application.services.IUrlContentProcessingService
import io.deepsearch.application.services.SearchEvent
import io.deepsearch.domain.models.entities.SearchFlowEvent
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.exceptions.MarkdownConversionException
import io.deepsearch.domain.exceptions.NetworkConnectionException
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.deepsearch.domain.ratelimit.IAdaptiveRateLimiter
import io.deepsearch.domain.models.valueobjects.AnswerStatus
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.UrlContentResult
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.domain.models.valueobjects.SearchMode
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SessionHistory
import io.deepsearch.domain.models.valueobjects.UncachedUrlAccess
import io.deepsearch.domain.services.INormalizeUrlService
import io.deepsearch.domain.ext.chunkedWithTimeout
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import io.deepsearch.application.services.FeedbackLoopReport
import io.deepsearch.application.services.IQueryProcessingService
import io.deepsearch.domain.exceptions.UrlProcessingException
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import kotlinx.coroutines.FlowPreview
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.timeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

interface IAgenticBrowserSearchOrchestrator : ISearchOrchestrator

/**
 * Orchestrates agentic search using a reactive flow-based approach.
 * Returns a Flow<SearchEvent> that emits events as the search progresses.
 * 
 * Delegates to facade services for:
 * - Link discovery (SERP, Hybrid, KG, File Search) via LinkDiscoveryFacadeService
 * - Source evaluation (HTML, PDF, Markdown) via SourceEvaluationFacadeService
 * - Answer synthesis via AnswerSynthesisFacadeService
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class AgenticBrowserSearchOrchestrator(
    // Infrastructure
    private val applicationScope: IApplicationCoroutineScope,
    // Facade services
    private val linkDiscoveryFacadeService: ILinkDiscoveryFacadeService,
    private val sourceEvaluationFacadeService: ISourceEvaluationFacadeService,
    private val answerSynthesisFacadeService: IAnswerSynthesisFacadeService,
    // URL Processing
    private val urlContentProcessingService: IUrlContentProcessingService,
    private val adaptiveRateLimiter: IAdaptiveRateLimiter,
    private val webpageMarkdownRepository: IWebpageMarkdownRepository,
    // Session/Tracking
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService,
    private val queryProcessingService: IQueryProcessingService,
    // Event tracking (for timeline visualization)
    private val searchFlowEventService: ISearchFlowEventService,
    // URL utilities
    private val normalizeUrlService: INormalizeUrlService
) : IAgenticBrowserSearchOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        // Default score for links from sources that don't provide scoring (SERPER, etc.)
        private const val DEFAULT_LINK_SCORE = 10

        /**
         * URL normalization config for deduplication.
         * Strips locale segments from paths so /about, /en/about, /zh-cn/about are treated as duplicates.
         * This prevents redundant processing of the same content in different locales.
         */
        private val DEDUP_URL_NORMALIZATION_CONFIG = io.deepsearch.domain.services.UrlNormalizationConfig(
            stripLocaleFromPath = true,
            normalizeWwwSubdomain = true
        )
    }

    @OptIn(FlowPreview::class)
    override fun execute(
        searchQuery: SearchQuery,
        maxCacheAge: Long?,
        apiKeyId: ApiKeyId,
        proxyConfig: ProxyConfiguration,
        sessionHistory: SessionHistory
    ): Flow<SearchEvent> = channelFlow {
        val budget = SearchBudget(timeLimitMs = 300 * 1000L, maxLinks = 200)
        val session = querySessionService.createSession(
            searchQuery.query,
            searchQuery.url,
            apiKeyId,
            SearchMode.LIVE_CRAWLING,
            budget,
            sessionHistory.previousSessionId,
            sessionHistory.rootSessionId
        )
        val sessionId = session.id

        try {
            // Emit session created (persists to timeline + SSE)
            emitSessionStarted(sessionId, searchQuery.query, searchQuery.url, channel)
            logger.debug("[{}] Executing search for query: {}", sessionId.value, searchQuery.query)

            // Query processing flow - runs in parallel with discovery (no latency penalty)
            // Uses Gemini URL Context tool to extract website context and generate expanded query + requirements
            // Shared as a hot flow so multiple collectors get the same cached result
            // When sessionHistory is provided, query processing is context-aware of prior queries/answers
            val queryProcessingFlow = queryProcessingService.processQueryFlow(searchQuery, maxCacheAge, sessionId, sessionHistory)
                .shareIn(this, SharingStarted.Eagerly, replay = 1)

            // Tracks URLs that have been fully processed (content fetched, markdown emitted)
            // Used for content processing dedup - each URL only gets processed once
            val processedUrls = ConcurrentHashMap.newKeySet<String>()

            // Tracks (query, URL) pairs that have had link discovery done
            // Used for link discovery dedup - allows re-analyzing same URL with different queries
            val discoveredQueryUrls = ConcurrentHashMap.newKeySet<QueryUrlKey>()

            // Query channel - the top-level driver for the search flow
            // Initial query is sent first, follow-up queries are added via the feedback loop
            val queryChannel = Channel<String>(Channel.UNLIMITED)
            queryChannel.send(searchQuery.query)

            // Track previously searched queries (thread-safe) for dedup context
            val previouslySearchedQueries = ConcurrentHashMap.newKeySet<String>()
            previouslySearchedQueries.add(searchQuery.query)

            // Priority buffer for discovered links - maintains global ordering by score
            val priorityLinkBuffer = PriorityLinkChannel(defaultScore = DEFAULT_LINK_SCORE)

            // Channel for recursive link discovery (links found within processed pages)
            val recursiveDiscoveredLinksChannel = Channel<DiscoveredLink>(Channel.UNLIMITED)
            
            // Track in-flight operations for idle detection
            val inFlightDiscovery = AtomicInteger(0)      // Queries being discovered (SERP/Hybrid/KG)
            val inFlightLinkProcessing = AtomicInteger(0) // Links being processed (fetch/extract)
            
            // Centralized idle check - called when any stage completes
            // Returns true if channels were closed (search is done)
            fun checkAndCloseIfIdle(): Boolean {
                // Not idle if any work is in progress
                if (inFlightDiscovery.get() > 0) return false
                if (inFlightLinkProcessing.get() > 0) return false
                
                // Not idle if any queues have pending work
                if (!queryChannel.isEmpty) return false
                if (!priorityLinkBuffer.isEmpty()) return false
                
                // All work is done - close channels to terminate the flow
                if (!priorityLinkBuffer.isClosedForSend()) {
                    logger.info(
                        "[{}] Search idle detected (discovery={}, linkProcessing={}, queryChannel=empty, buffer=empty) - closing channels",
                        sessionId.value, inFlightDiscovery.get(), inFlightLinkProcessing.get()
                    )
                    queryChannel.close()
                    priorityLinkBuffer.close()
                    return true
                }
                return false
            }

            // Discovery flows - unified query-driven discovery
            // All queries (initial + follow-up) flow through the same pipeline:
            // queryChannel → full discovery (SERP + Hybrid + KG + File) → priorityLinkBuffer
            val discoveryFlows = merge(
                // Initial URL processing (user-provided URL, always runs once)
                processInitialLinkFlow(
                    sessionId,
                    searchQuery,
                    processedUrls,
                    priorityLinkBuffer,
                    maxCacheAge,
                    proxyConfig,
                    channel
                ),
                // Query-driven discovery - triggers for ALL queries (initial + follow-up)
                // Runs SERP + Hybrid + KG + File search in parallel for each query
                processQueryDrivenDiscoveryFlow(
                    sessionId,
                    searchQuery,
                    queryChannel,
                    priorityLinkBuffer,
                    inFlightDiscovery,
                    ::checkAndCloseIfIdle
                )
            )

            // Link processing flow - pulls from priority buffer and processes URLs
            val linkProcessingFlow = processPriorityLinksFlow(
                sessionId,
                searchQuery,
                processedUrls,
                discoveredQueryUrls,
                priorityLinkBuffer,
                recursiveDiscoveredLinksChannel,
                inFlightLinkProcessing,
                ::checkAndCloseIfIdle,
                maxCacheAge,
                proxyConfig,
                channel
            )

            // Create a child job for source processing that can be cancelled
            // when synthesis produces FINISH_SEARCH
            val sourceProcessingJob = Job(coroutineContext[Job])
            val sourceProcessingScope = CoroutineScope(coroutineContext + sourceProcessingJob)

            // Track current accumulator state using AtomicReference so it's accessible
            // in the sourceFlow completion handler
            val accumulatorRef = AtomicReference(
                SourceAccumulator(searchedQueries = listOf(searchQuery.query))
            )

            // Ensure exactly-once session completion
            val sessionCompleted = AtomicBoolean(false)

            // Merge discovery and link processing into a single source flow
            // onCompletion handler triggers finishQuerySession when all channels close (LINKS_EXHAUSTED)
            val sourceFlow = merge(discoveryFlows, linkProcessingFlow)
                .cancellable()
                .onCompletion { cause ->
                    if (cause == null && sessionCompleted.compareAndSet(false, true)) {
                        logger.info("[{}] Source flow completed normally - links exhausted, finalizing session", sessionId.value)
                        try {
                            finishQuerySession(sessionId, searchQuery, accumulatorRef.get(), channel)
                        } catch (e: Exception) {
                            logger.error("[{}] Error finalizing session on source completion: {}", sessionId.value, e.message, e)
                        }
                    }
                }
                .shareIn(sourceProcessingScope, SharingStarted.Eagerly)

            // When includeImages is enabled, skip preview path evaluation
            val includeImages = searchQuery.includeImages
            if (includeImages) {
                logger.info("[{}] includeImages enabled, skipping preview evaluation", sessionId.value)
            }

            // Collect query processing result (runs in parallel with discovery, should be ready by now)
            val queryProcessingResult = queryProcessingFlow.first()
            val expandedQuery = queryProcessingResult.expandedQuery
            val fulfillmentRequirements = queryProcessingResult.fulfillmentRequirements

            logger.info(
                "[{}] Query processing complete: '{}' → '{}' with {} requirements",
                sessionId.value,
                searchQuery.query,
                expandedQuery,
                fulfillmentRequirements.size
            )

            // Emit follow-up queries from breakdown agent to discovery channel for early link discovery
            if (queryProcessingResult.followUpQueries.isNotEmpty()) {
                val newFollowUpQueries = queryProcessingResult.followUpQueries.filter { query ->
                    previouslySearchedQueries.add(query)
                }
                if (newFollowUpQueries.isNotEmpty()) {
                    logger.info(
                        "[{}] Follow-up queries from query processing: {}",
                        sessionId.value, newFollowUpQueries
                    )
                    newFollowUpQueries.forEach { query ->
                        queryChannel.send(query)
                    }
                }
            }

            // Unified evaluation flow: preview (HTML/PDF) and markdown sources are evaluated in parallel
            // and merged into a single stream of EvaluatedSource (isPreview set by eval agents)
            val unifiedEvalFlow = merge(
                // HTML Preview path: fast HTML evaluation (when includeImages is false)
                if (!includeImages) {
                    sourceFlow
                        .filterIsInstance<UrlContentResult.HtmlPreview>()
                        .flatMapMerge(concurrency = 100) { htmlSource ->
                            flow {
                                val evalResult = sourceEvaluationFacadeService.evaluateHtmlSource(
                                    sessionId, htmlSource, expandedQuery, fulfillmentRequirements
                                )
                                if (evalResult != null) {
                                    emit(evalResult) // isPreview=true set by HtmlSourceEvalAgent
                                }
                            }
                        }
                } else {
                    flowOf() // Empty flow when includeImages is enabled
                },

                // PDF Preview path: fast PDF evaluation using local text extraction
                // Provides early results while Gemini upload runs
                // Note: PDF text extraction doesn't include images, so this runs regardless of includeImages
                sourceFlow
                    .filterIsInstance<UrlContentResult.PdfPreview>()
                    .filter { it.extractedText.isNotBlank() }
                    .flatMapMerge(concurrency = 100) { pdfSource ->
                        flow {
                            val evalResult = sourceEvaluationFacadeService.evaluatePdfSource(
                                sessionId, pdfSource, expandedQuery, fulfillmentRequirements
                            )
                            if (evalResult != null) {
                                emit(evalResult) // isPreview=true set by PdfSourceEvalAgent
                            }
                        }
                    },

                // Markdown path: full markdown evaluation (replaces preview when it arrives)
                sourceFlow
                    .filterIsInstance<UrlContentResult.FullMarkdown>()
                    .filter { it.markdown.isNotBlank() }
                    .flatMapMerge(concurrency = 100) { fullMarkdown ->
                        flow {
                            val markdownSource = MarkdownSource(fullMarkdown.url, fullMarkdown.title, fullMarkdown.description, fullMarkdown.markdown)
                            val evalResult = sourceEvaluationFacadeService.evaluateMarkdownSource(
                                sessionId, markdownSource, expandedQuery, fulfillmentRequirements,
                                imageMapping = fullMarkdown.imageMapping
                            )
                            if (evalResult != null) {
                                emit(evalResult) // isPreview=false (default)
                            }
                        }
                    }
            )

            // Initialize accumulator with initial requirements from query processing
            // Requirements may be refined during synthesis based on discovered content
            accumulatorRef.set(
                accumulatorRef.get().copy(currentRequirements = fulfillmentRequirements)
            )

            // Batch sources with timeout, then accumulate and synthesize
            unifiedEvalFlow
                .chunkedWithTimeout(chunkSize = 100, timeoutMs = 300)
                .takeWhile { !accumulatorRef.get().isComplete }
                .runningFold(accumulatorRef.get()) { state, batch ->
                    aggregateBatchIntoAccumulator(
                        sessionId, state, batch, channel,
                        expandedQuery,
                        sessionHistory
                    )
                }
                .onEach { accumulator ->
                    accumulatorRef.set(accumulator)

                    // Check budget - if exceeded, mark session complete to exit flow via takeWhile
                    if (querySessionService.isBudgetExceeded(sessionId)) {
                        if (sessionCompleted.compareAndSet(false, true)) {
                            logger.info("[{}] Budget exceeded (maxLinks), completing session", sessionId.value)
                            sourceProcessingJob.cancel()
                            finishQuerySession(sessionId, searchQuery, accumulator, channel)
                        }
                        return@onEach
                    }

                    // Process follow-up queries from synthesis (already deduped by synthesis agent)
                    if (accumulator.pendingFollowUpQueries.isNotEmpty() &&
                        accumulator.status == AnswerStatus.CONTINUE_SEARCH
                    ) {
                        // Filter out queries we've already searched (thread-safe)
                        val newQueries = accumulator.pendingFollowUpQueries.filter { query ->
                            previouslySearchedQueries.add(query)
                        }

                        if (newQueries.isNotEmpty()) {
                            logger.info("[{}] Follow-up queries from synthesis: {}", sessionId.value, newQueries)

                            emitFollowUpSearchStarted(
                                sessionId = sessionId,
                                followUpQueries = newQueries,
                                whatsMissing = "Follow-up queries",
                                iterationNumber = accumulator.iterationNumber,
                                eventChannel = channel
                            )

                            // Send to query channel to trigger discovery
                            newQueries.forEach { query -> queryChannel.send(query) }
                        }
                    }
                    // Note: CONTINUE_SEARCH with no follow-ups is handled by checkAndCloseIfIdle()
                    // which is called when discovery or link processing completes

                    // Handle session completion (synthesis returned FINISH_SEARCH)
                    if (accumulator.isComplete && sessionCompleted.compareAndSet(false, true)) {
                        logger.info("[{}] Synthesis returned FINISH_SEARCH, completing session", sessionId.value)
                        sourceProcessingJob.cancel()
                        finishQuerySession(sessionId, searchQuery, accumulator, channel)
                    }
                }
                .takeWhile { !sessionCompleted.get() }
                .timeout(budget.timeLimitMs.milliseconds)
                .onCompletion { cause ->
                    // Single place handles ALL completion scenarios:
                    // - cause == null: normal completion (FINISH_SEARCH or LINKS_EXHAUSTED)
                    // - cause is TimeoutCancellationException: budget time exceeded
                    if (sessionCompleted.compareAndSet(false, true)) {
                        val reason = when (cause) {
                            is TimeoutCancellationException -> "Budget timeout reached"
                            null -> "Flow completed (links exhausted)"
                            else -> "Flow ended (${cause.message})"
                        }
                        logger.info("[{}] {}, finalizing session", sessionId.value, reason)
                        sourceProcessingJob.cancel()
                        // Use NonCancellable to ensure session is finalized even during cancellation
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            finishQuerySession(sessionId, searchQuery, accumulatorRef.get(), channel)
                        }
                    }
                }
                .collect()
        } catch (e: CancellationException) {
            logger.debug("[{}] Flow cancelled", sessionId.value)
        } catch (e: UrlProcessingException) {
            // Use categorized error for URL processing failures
            logger.error("[{}] URL processing error: {}", sessionId.value, e.message, e)
            querySessionService.completeSessionWithError(sessionId, e.message ?: "Unknown error")
            emitSessionErrorFromException(
                sessionId = sessionId,
                exception = e,
                eventChannel = channel
            )
        } catch (e: Exception) {
            logger.error("[{}] Error in execute: {}", sessionId.value, e.message, e)
            querySessionService.completeSessionWithError(sessionId, e.message ?: "Unknown error")
            emitSessionError(
                sessionId = sessionId,
                errorType = e::class.simpleName ?: "Unknown",
                errorMessage = e.message ?: "Unknown error",
                eventChannel = channel
            )
        }
    }

    /**
     * Process the initial user-provided URL.
     */
    private fun processInitialLinkFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        processedUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        priorityLinkBuffer: PriorityLinkChannel,
        maxCacheAge: Long?,
        proxyConfig: ProxyConfiguration,
        eventChannel: SendChannel<SearchEvent>
    ): Flow<UrlContentResult> {
        return flowOf(searchQuery.url)
            .flatMapMerge { url ->
                val normalizedUrl = normalizeUrlService.normalize(url) ?: url
                // Add URL to processedUrls before processing to prevent duplicate processing
                // This must happen before processUrlAsFlow, not on each event, because
                // multiple events (HtmlPreview, LinkDiscovery, MarkdownExtraction)
                // share the same URL and would filter out subsequent events
                processedUrls.add(normalizedUrl)
                emitUrlProcessingStarted(sessionId, normalizedUrl, eventChannel)

                urlContentProcessingService.processUrlAsFlow(
                    normalizedUrl,
                    searchQuery.query,
                    maxCacheAge,
                    sessionId,
                    searchQuery.ocrLanguage,
                    proxyConfig
                )
                    .onEach { event ->
                        when (event) {
                            is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                // Send discovered links to priority buffer with initial query context
                                event.discoveredLinks.forEach { link ->
                                    priorityLinkBuffer.send(DiscoveredLink(link, searchQuery.query))
                                }
                            }

                            is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                // Record URL access based on cached status
                                if (event.wasCached) {
                                    urlAccessService.recordUrlAccess(
                                        sessionId,
                                        CachedUrlAccess(event.url, Clock.System.now())
                                    )
                                }
                                // Emit UrlProcessed for all markdown extractions (cached and uncached)
                                emitUrlProcessed(
                                    sessionId = sessionId,
                                    url = event.url,
                                    title = event.title,
                                    description = event.description,
                                    markdownLength = event.markdown.length,
                                    wasCached = event.wasCached,
                                    eventChannel = eventChannel
                                )
                            }

                            is IUrlContentProcessingService.UrlProcessingEvent.HtmlPreviewReady -> {
                                // Record URL access for live crawled URLs (preview path is silent, no events)
                                urlAccessService.recordUrlAccess(
                                    sessionId,
                                    UncachedUrlAccess(event.url, Clock.System.now())
                                )
                                logger.debug(
                                    "[{}] HTML preview ready for {}: {} chars",
                                    sessionId.value, event.url, event.cleanedHtml.length
                                )
                            }

                            is IUrlContentProcessingService.UrlProcessingEvent.PdfPreviewReady -> {
                                // Record URL access for PDF preview (fast path)
                                urlAccessService.recordUrlAccess(
                                    sessionId,
                                    UncachedUrlAccess(event.url, Clock.System.now())
                                )
                                logger.debug(
                                    "[{}] PDF preview ready for {}: {} pages, {} chars",
                                    sessionId.value, event.url, event.pageCount, event.extractedText.length
                                )
                            }
                        }
                    }
                    .filter { event ->
                        event is IUrlContentProcessingService.UrlProcessingEvent.HtmlPreviewReady ||
                                event is IUrlContentProcessingService.UrlProcessingEvent.PdfPreviewReady ||
                                event is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete
                    }
                    .map { event ->
                        when (event) {
                            is IUrlContentProcessingService.UrlProcessingEvent.HtmlPreviewReady ->
                                UrlContentResult.HtmlPreview(
                                    event.url,
                                    event.title,
                                    event.description,
                                    event.cleanedHtml
                                )

                            is IUrlContentProcessingService.UrlProcessingEvent.PdfPreviewReady ->
                                UrlContentResult.PdfPreview(
                                    event.url,
                                    event.title,
                                    "PDF document with ${event.pageCount} pages",
                                    event.extractedText,
                                    event.pageCount
                                )

                            is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete ->
                                UrlContentResult.FullMarkdown(event.url, event.title, event.description, event.markdown, event.imageMapping)

                            else -> throw IllegalStateException("Unexpected event type")
                        }
                    }
            }
    }

    /**
     * Process queries from the query channel and trigger full link discovery.
     * Each query gets the same treatment as the initial query: SERP + Hybrid + KG + File Search.
     * Delegates to LinkDiscoveryFacadeService for the actual discovery.
     */
    private fun processQueryDrivenDiscoveryFlow(
        sessionId: QuerySessionId,
        baseSearchQuery: SearchQuery,
        queryChannel: Channel<String>,
        priorityLinkBuffer: PriorityLinkChannel,
        inFlightDiscovery: AtomicInteger,
        checkAndCloseIfIdle: () -> Boolean
    ): Flow<Unit> {
        return queryChannel.receiveAsFlow()
            .flatMapMerge(concurrency = 3) { query ->
                inFlightDiscovery.incrementAndGet()
                logger.info("[{}] Triggering full discovery for query: '{}' (inFlightDiscovery={})", sessionId.value, query, inFlightDiscovery.get())

                linkDiscoveryFacadeService.discoverLinksForQuery(
                    query = query,
                    baseSearchQuery = baseSearchQuery,
                    sessionId = sessionId,
                    onLinkDiscovered = { discoveredLink ->
                        priorityLinkBuffer.send(discoveredLink)
                    }
                ).onCompletion {
                    val remaining = inFlightDiscovery.decrementAndGet()
                    logger.debug("[{}] Discovery completed for query '{}' (inFlightDiscovery={})", sessionId.value, query, remaining)
                    checkAndCloseIfIdle()
                }
            }
    }

    /**
     * Process links from priority buffer in priority order.
     * Pulls from the priority buffer (highest score first) and processes URLs.
     * 
     * Implements two-level deduplication:
     * 1. (query, URL) dedup via discoveredQueryUrls - allows re-analyzing same URL with different queries
     * 2. URL dedup via processedUrls - ensures content is only fetched/emitted once per URL
     * 
     * When a URL is already processed but discovered by a new query:
     * - Retrieves cached HTML from WebpageMarkdownRepository
     * - Re-runs link relevance analysis with the new query context (via LinkDiscoveryFacadeService)
     * - Discovered links are sent to recursive channel
     * - No markdown is re-emitted (already done on first processing)
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun processPriorityLinksFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        processedUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        discoveredQueryUrls: ConcurrentHashMap.KeySetView<QueryUrlKey, Boolean>,
        priorityLinkBuffer: PriorityLinkChannel,
        recursiveChannel: Channel<DiscoveredLink>,
        inFlightLinkProcessing: AtomicInteger,
        checkAndCloseIfIdle: () -> Boolean,
        maxCacheAge: Long?,
        proxyConfig: ProxyConfiguration,
        eventChannel: SendChannel<SearchEvent>
    ): Flow<UrlContentResult> {
        // Track URLs currently being processed (for event status)
        val inFlight = ConcurrentHashMap.newKeySet<String>()

        // Merge priority buffer with recursive discoveries
        // When priority buffer completes (closes), close recursive channel
        return merge(
            priorityLinkBuffer.receiveAsFlow().onCompletion {
                logger.info("[{}] Priority buffer flow completed - closing recursiveChannel", sessionId.value)
                recursiveChannel.close()
            },
            recursiveChannel.receiveAsFlow()
        )
            .flatMapMerge(concurrency = 100) { discoveredLink ->
                flow {
                    val query = discoveredLink.query

                    // Normalize URL with locale stripping for deduplication
                    val dedupKey = normalizeUrlService.normalize(discoveredLink.url, DEDUP_URL_NORMALIZATION_CONFIG)
                        ?: normalizeUrlService.normalize(discoveredLink.url)
                        ?: discoveredLink.url
                    val url = normalizeUrlService.normalize(discoveredLink.url) ?: discoveredLink.url

                    // Apply language filter if configured
                    val languagePattern = searchQuery.parsedLanguagePattern
                    if (languagePattern != null) {
                        if (!languagePattern.matches(url, searchQuery.url)) {
                            logger.debug("[{}] Link filtered by language pattern: {}", sessionId.value, url)
                            return@flow
                        }
                    }

                    // 1. Check (query, URL) dedup - skip if already handled this pair
                    val queryUrlKey = QueryUrlKey(query, dedupKey)
                    if (!discoveredQueryUrls.add(queryUrlKey)) {
                        logger.debug(
                            "[{}] Skipping already-analyzed (query, URL) pair: query='{}', url='{}'",
                            sessionId.value,
                            query,
                            dedupKey
                        )
                        return@flow
                    }

                    // 2. Check if URL was already processed (has cached content)
                    if (processedUrls.contains(dedupKey)) {
                        // Re-run link relevance analysis on CACHED HTML with new query context
                        logger.debug(
                            "[{}] Re-analyzing cached HTML for URL '{}' with query '{}'",
                            sessionId.value,
                            url,
                            query
                        )
                        try {
                            val cached = webpageMarkdownRepository.findByUrl(dedupKey)
                            val cachedHtml = cached?.cleanedLinkRelevanceHtml
                            if (cachedHtml != null) {
                                val newLinks = linkDiscoveryFacadeService.reanalyzeCachedHtml(
                                    query = query,
                                    cachedHtml = cachedHtml,
                                    url = dedupKey,
                                    sessionId = sessionId
                                )
                                logger.debug(
                                    "[{}] Re-analysis discovered {} links for URL '{}' with query '{}'",
                                    sessionId.value, newLinks.size, url, query
                                )
                                // Send newly discovered links to recursive channel with query context
                                newLinks.forEach { newLink ->
                                    recursiveChannel.send(DiscoveredLink(newLink, query))
                                }
                            } else {
                                logger.debug("[{}] No cached HTML available for re-analysis: {}", sessionId.value, url)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn(
                                "[{}] Failed to re-analyze cached HTML for {}: {}",
                                sessionId.value,
                                url,
                                e.message
                            )
                        }
                        return@flow  // Skip markdown emission (already done on first processing)
                    }

                    // 3. New URL: full processing (fetch + analyze + markdown)
                    processedUrls.add(dedupKey)
                    inFlight.add(url)
                    inFlightLinkProcessing.incrementAndGet()
                    emitUrlProcessingStarted(sessionId, url, eventChannel)

                    // Use adaptive rate limiter to respect website rate limits
                    adaptiveRateLimiter.withRateLimit(url) {
                        urlContentProcessingService.processUrlAsFlow(
                            url,
                            searchQuery.query,
                            maxCacheAge,
                            sessionId,
                            searchQuery.ocrLanguage,
                            proxyConfig
                        )
                            .catch { e ->
                                when (e) {
                                    is CancellationException -> throw e
                                    is NetworkConnectionException, is MarkdownConversionException -> {
                                        urlAccessService.recordUrlAccess(
                                            sessionId, FailedUrlAccess(
                                                url = e.url, timestamp = Clock.System.now(),
                                                exceptionType = e::class.simpleName!!, message = e.reason
                                            )
                                        )
                                        inFlight.remove(e.url)
                                        inFlightLinkProcessing.decrementAndGet()
                                        emitUrlProcessingFailed(sessionId, e.url, e.reason, eventChannel)
                                        checkAndCloseIfIdle()
                                    }

                                    else -> throw e
                                }
                            }
                            .onEach { event ->
                                when (event) {
                                    is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                        // Send newly discovered links to recursive channel with query context
                                        // Only send if channel is still open
                                        if (!recursiveChannel.isClosedForSend) {
                                            event.discoveredLinks.forEach { newLink ->
                                                try {
                                                    recursiveChannel.send(DiscoveredLink(newLink, query))
                                                } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
                                                    // Channel closed while sending - this is expected during shutdown
                                                    logger.debug("[{}] Recursive channel closed, skipping link: {}", sessionId.value, newLink)
                                                }
                                            }
                                        }
                                    }

                                    is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                        if (event.wasCached) {
                                            urlAccessService.recordUrlAccess(
                                                sessionId,
                                                CachedUrlAccess(event.url, Clock.System.now())
                                            )
                                        }
                                        emitUrlProcessed(
                                            sessionId = sessionId,
                                            url = event.url,
                                            title = event.title,
                                            description = event.description,
                                            markdownLength = event.markdown.length,
                                            wasCached = event.wasCached,
                                            eventChannel = eventChannel
                                        )
                                        inFlight.remove(event.url)
                                        inFlightLinkProcessing.decrementAndGet()
                                        checkAndCloseIfIdle()
                                    }

                                    is IUrlContentProcessingService.UrlProcessingEvent.HtmlPreviewReady -> {
                                        urlAccessService.recordUrlAccess(
                                            sessionId,
                                            UncachedUrlAccess(event.url, Clock.System.now())
                                        )
                                    }

                                    is IUrlContentProcessingService.UrlProcessingEvent.PdfPreviewReady -> {
                                        urlAccessService.recordUrlAccess(
                                            sessionId,
                                            UncachedUrlAccess(event.url, Clock.System.now())
                                        )
                                    }
                                }
                            }
                            .filter { event ->
                                event is IUrlContentProcessingService.UrlProcessingEvent.HtmlPreviewReady ||
                                        event is IUrlContentProcessingService.UrlProcessingEvent.PdfPreviewReady ||
                                        event is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete
                            }
                            .map { event ->
                                when (event) {
                                    is IUrlContentProcessingService.UrlProcessingEvent.HtmlPreviewReady ->
                                        UrlContentResult.HtmlPreview(
                                            event.url,
                                            event.title,
                                            event.description,
                                            event.cleanedHtml
                                        )

                                    is IUrlContentProcessingService.UrlProcessingEvent.PdfPreviewReady ->
                                        UrlContentResult.PdfPreview(
                                            event.url,
                                            event.title,
                                            "PDF document with ${event.pageCount} pages",
                                            event.extractedText,
                                            event.pageCount
                                        )

                                    is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete ->
                                        UrlContentResult.FullMarkdown(
                                            event.url,
                                            event.title,
                                            event.description,
                                            event.markdown,
                                            event.imageMapping
                                        )

                                    else -> throw IllegalStateException("Unexpected event type")
                                }
                            }
                            .collect { emit(it) }
                    }
                }
                // Note: Don't close channels in onCompletion here - let the main flow handle it
                // after synthesis has had a chance to generate follow-up queries
            }
    }

    /**
     * Accumulator for unified answer generation with feedback loop support.
     * Uses URL-keyed source map where markdown sources replace preview sources.
     * Tracks sources, queries, requirements, and synthesis iterations.
     */
    private data class SourceAccumulator(
        /** URL-keyed source map - markdown (isPreview=false) replaces preview (isPreview=true) */
        val sourcesByUrl: Map<String, EvaluatedSource> = emptyMap(),
        /** Whether the answer is complete (status=FINISH_SEARCH from synthesis agent) */
        val isComplete: Boolean = false,
        /** Full answer from the last answer synthesis attempt */
        val fullAnswer: String? = null,
        /** URLs of sources that were actually cited in the answer */
        val citedSourceUrls: List<String> = emptyList(),
        /** Image IDs from the answer */
        val imageIds: List<String> = emptyList(),
        /** All queries that have been searched (for deduplication in synthesis prompt) */
        val searchedQueries: List<String> = emptyList(),
        /** Current synthesis iteration number */
        val iterationNumber: Int = 0,
        /** Sources count per iteration for loop report */
        val sourcesPerIteration: List<Int> = emptyList(),
        /** Answer status from last synthesis */
        val status: AnswerStatus = AnswerStatus.CONTINUE_SEARCH,
        /** Follow-up queries from latest synthesis to be emitted upstream */
        val pendingFollowUpQueries: List<String> = emptyList(),
        /** Current fulfillment requirements (may be refined from original based on discovered content) */
        val currentRequirements: List<String> = emptyList()
    ) {
        /** Get all evaluated sources as a list (for synthesis agent) */
        val evaluatedSources: List<EvaluatedSource> get() = sourcesByUrl.values.toList()

        /** Get all processed URLs */
        val processedUrls: Set<String> get() = sourcesByUrl.keys
    }

    /**
     * Aggregate a batch of evaluated sources into the accumulator with URL-keyed replacement.
     * Markdown sources (isPreview=false) replace preview sources (isPreview=true) for the same URL.
     * Triggers answer synthesis (via AnswerSynthesisFacadeService) after updating sources.
     * 
     * Requirements are refined by the synthesis agent based on discovered content:
     * - Uses state.currentRequirements for synthesis (which may have been refined in previous iterations)
     * - Updates currentRequirements if synthesis returns non-empty refinedRequirements
     */
    private suspend fun aggregateBatchIntoAccumulator(
        sessionId: QuerySessionId,
        state: SourceAccumulator,
        batch: List<EvaluatedSource>,
        eventChannel: SendChannel<SearchEvent>,
        expandedQuery: String,
        sessionHistory: SessionHistory = SessionHistory.empty()
    ): SourceAccumulator {
        if (batch.isEmpty()) {
            return state
        }

        // Build updated source map with replacement logic
        val updatedSourcesByUrl = state.sourcesByUrl.toMutableMap()
        var replacements = 0
        var additions = 0

        for (source in batch) {
            val url = source.url
            val existing = updatedSourcesByUrl[url]

            when {
                // No existing entry - add new source
                existing == null -> {
                    updatedSourcesByUrl[url] = source
                    additions++
                }
                // Existing is preview and new is markdown - replace
                existing.isPreview && !source.isPreview -> {
                    updatedSourcesByUrl[url] = source
                    replacements++
                    logger.debug(
                        "[{}] Replacing preview with markdown for URL: {}",
                        sessionId.value, url
                    )
                }
                // Existing is markdown - keep existing (markdown is authoritative)
                !existing.isPreview -> {
                    // Skip - markdown already present
                }
                // Both are preview - keep existing
                else -> {
                    // Skip - first preview wins
                }
            }
        }

        logger.debug(
            "[{}] Batch processing: {} sources, {} additions, {} replacements",
            sessionId.value, batch.size, additions, replacements
        )

        // Synthesize answer with updated sources (via facade service)
        // Uses current requirements which may have been refined in previous iterations
        val synthesis = answerSynthesisFacadeService.synthesizeAnswer(
            sessionId = sessionId,
            expandedQuery = expandedQuery,
            evaluatedSources = updatedSourcesByUrl.values.toList(),
            previouslySearchedQueries = state.searchedQueries,
            fulfillmentRequirements = state.currentRequirements,
            sessionHistory = sessionHistory
        )

        val newIterationNumber = state.iterationNumber + 1
        val updatedSourcesPerIteration = state.sourcesPerIteration + updatedSourcesByUrl.size

        // Update requirements if synthesis returned refined ones
        val updatedRequirements = if (synthesis.refinedRequirements.isNotEmpty()) {
            logger.info(
                "[{}] Requirements refined: {} → {} (was: {}, now: {})",
                sessionId.value,
                state.currentRequirements.size,
                synthesis.refinedRequirements.size,
                state.currentRequirements,
                synthesis.refinedRequirements
            )
            synthesis.refinedRequirements
        } else {
            state.currentRequirements
        }

        logger.debug(
            "[{}] Synthesis iteration {}: {} sources, status={}, citedSources={}, followUpQueries={}, requirements={}",
            sessionId.value, newIterationNumber, updatedSourcesByUrl.size, synthesis.status,
            synthesis.citedSourceUrls.size, synthesis.followUpQueries.size, updatedRequirements.size
        )

        // Emit synthesis iteration event (persists to timeline + SSE)
        emitSynthesisComplete(
            sessionId = sessionId,
            iterationNumber = newIterationNumber,
            status = synthesis.status.name,
            sourceCount = updatedSourcesByUrl.size,
            followUpQueries = synthesis.followUpQueries,
            eventChannel = eventChannel
        )

        // Emit sources evaluated event (persists to timeline + SSE)
        emitSourcesEvaluated(
            sessionId = sessionId,
            processedUrlCount = updatedSourcesByUrl.size,
            relevantCount = updatedSourcesByUrl.size,
            isGoodEnough = synthesis.isComplete,
            reason = if (synthesis.isComplete) "Answer is complete"
            else "Collecting more sources (unsatisfied: ${synthesis.getUnsatisfiedSummary()})",
            eventChannel = eventChannel
        )

        // Capture follow-up queries for upstream processing
        val pendingFollowUpQueries = if (synthesis.status == AnswerStatus.CONTINUE_SEARCH) {
            synthesis.followUpQueries
        } else {
            emptyList()
        }

        return SourceAccumulator(
            sourcesByUrl = updatedSourcesByUrl,
            isComplete = synthesis.isComplete,
            fullAnswer = synthesis.answer,
            citedSourceUrls = synthesis.citedSourceUrls,
            imageIds = synthesis.imageIds,
            searchedQueries = state.searchedQueries,
            iterationNumber = newIterationNumber,
            sourcesPerIteration = updatedSourcesPerIteration,
            status = synthesis.status,
            pendingFollowUpQueries = pendingFollowUpQueries,
            currentRequirements = updatedRequirements
        )
    }

    private suspend fun finishQuerySession(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        accumulator: SourceAccumulator,
        eventChannel: SendChannel<SearchEvent>,
        loopReport: FeedbackLoopReport? = null
    ) {
        // Check if session is already finished (use DB as source of truth)
        val session = querySessionService.getSession(sessionId)
        if (session.finishReason != null) {
            logger.debug(
                "[{}] Session already finished with {}, skipping query session finish",
                sessionId.value,
                session.finishReason
            )
            return
        }

        val fullAnswer = accumulator.fullAnswer ?: "No information found to answer the query."
        val imageIds = accumulator.imageIds
        val answerFound = accumulator.fullAnswer?.isNotBlank() == true

        // Build loop report from accumulator if not provided
        val finalLoopReport = loopReport ?: FeedbackLoopReport(
            totalIterations = accumulator.iterationNumber,
            followUpQueries = accumulator.searchedQueries.drop(1),  // Exclude original query
            sourcesPerIteration = accumulator.sourcesPerIteration,
            finalStatus = accumulator.status.name,
            totalSynthesisCalls = accumulator.iterationNumber,
            durationMs = 0  // Will be calculated in sessionDetail
        )

        // Log the loop report
        logger.info(
            "[{}] Feedback Loop Report: iterations={}, followUpQueries={}, sourcesPerIteration={}, finalStatus={}, synthesisCalls={}",
            sessionId.value,
            finalLoopReport.totalIterations,
            finalLoopReport.followUpQueries,
            finalLoopReport.sourcesPerIteration,
            finalLoopReport.finalStatus,
            finalLoopReport.totalSynthesisCalls
        )

        emitAnswerChunk(sessionId, fullAnswer, eventChannel)
        finishSessionWithAnswer(
            sessionId,
            accumulator,
            eventChannel,
            fullAnswer,
            answerFound,
            imageIds,
            finalLoopReport
        )
    }

    private suspend fun finishSessionWithAnswer(
        sessionId: QuerySessionId,
        accumulator: SourceAccumulator,
        eventChannel: SendChannel<SearchEvent>,
        fullAnswer: String,
        answerFound: Boolean,
        imageIds: List<String>,
        loopReport: FeedbackLoopReport? = null
    ) {
        // Use cited sources if available, otherwise fall back to all sources
        val answerSources = accumulator.citedSourceUrls.ifEmpty {
            accumulator.evaluatedSources.map { it.url }
        }
        if (answerSources.isNotEmpty()) {
            urlAccessService.markUrlsAsUsedInAnswer(sessionId, answerSources)
        }

        val finishReason = when {
            accumulator.isComplete -> "ANSWER_COMPLETE"
            querySessionService.isBudgetExceeded(sessionId) -> "BUDGET_EXCEEDED"
            else -> "LINKS_EXHAUSTED"
        }

        when (finishReason) {
            "ANSWER_COMPLETE" -> querySessionService.completeSessionAnswerComplete(
                sessionId,
                fullAnswer,
                answerFound,
                imageIds
            )

            "BUDGET_EXCEEDED" -> querySessionService.completeSessionBudgetExceeded(
                sessionId,
                fullAnswer,
                answerFound,
                imageIds
            )

            else -> querySessionService.completeSessionLinksExhausted(
                sessionId,
                fullAnswer,
                answerFound,
                imageIds
            )
        }

        // Fetch full session detail for the completed event
        val sessionDetail = querySessionService.getSessionDetailInternal(sessionId)

        eventChannel.send(
            SearchEvent.SessionCompleted(
                sessionId = sessionId,
                finishReason = finishReason,
                sessionDetail = sessionDetail,
                imageIds = imageIds,
                loopReport = loopReport
            )
        )
    }

    // ==================== Event Emission Helpers ====================
    // These helpers emit SearchFlowEvents for timeline persistence and
    // map them to SearchEvents for SSE streaming.

    /**
     * Emit a SearchFlowEvent, persist it for timeline, and send the mapped SearchEvent to the channel.
     * If the event doesn't have an SSE equivalent, only persistence happens.
     */
    private suspend fun emitFlowEvent(
        event: SearchFlowEvent,
        eventChannel: SendChannel<SearchEvent>
    ) {
        val searchEvent = searchFlowEventService.emit(event)
        searchEvent?.let { eventChannel.send(it) }
    }

    /**
     * Emit SESSION_STARTED event.
     */
    private suspend fun emitSessionStarted(
        sessionId: QuerySessionId,
        query: String,
        url: String,
        eventChannel: SendChannel<SearchEvent>
    ) {
        emitFlowEvent(
            SearchFlowEvent.SessionStarted(
                sessionId = sessionId,
                query = query,
                url = url
            ),
            eventChannel
        )
    }

    /**
     * Emit URL_PROCESSING_STARTED event.
     */
    private suspend fun emitUrlProcessingStarted(
        sessionId: QuerySessionId,
        url: String,
        eventChannel: SendChannel<SearchEvent>
    ) {
        emitFlowEvent(
            SearchFlowEvent.UrlProcessingStarted(
                sessionId = sessionId,
                url = url
            ),
            eventChannel
        )
    }

    /**
     * Emit URL_MARKDOWN_COMPLETE event (successful URL processing).
     */
    private suspend fun emitUrlProcessed(
        sessionId: QuerySessionId,
        url: String,
        title: String?,
        description: String?,
        markdownLength: Int,
        wasCached: Boolean,
        eventChannel: SendChannel<SearchEvent>
    ) {
        emitFlowEvent(
            SearchFlowEvent.UrlMarkdownComplete(
                sessionId = sessionId,
                url = url,
                title = title,
                description = description,
                markdownLength = markdownLength,
                accessType = if (wasCached) "CACHED" else "UNCACHED",
                wasCached = wasCached
            ),
            eventChannel
        )
    }

    /**
     * Emit URL_PROCESSING_FAILED event.
     */
    private suspend fun emitUrlProcessingFailed(
        sessionId: QuerySessionId,
        url: String,
        errorMessage: String?,
        eventChannel: SendChannel<SearchEvent>
    ) {
        emitFlowEvent(
            SearchFlowEvent.UrlProcessingFailed(
                sessionId = sessionId,
                url = url,
                errorMessage = errorMessage ?: "Unknown error"
            ),
            eventChannel
        )
    }

    /**
     * Emit SOURCES_EVALUATED event.
     */
    private suspend fun emitSourcesEvaluated(
        sessionId: QuerySessionId,
        processedUrlCount: Int,
        relevantCount: Int,
        isGoodEnough: Boolean,
        reason: String?,
        eventChannel: SendChannel<SearchEvent>
    ) {
        emitFlowEvent(
            SearchFlowEvent.SourcesEvaluated(
                sessionId = sessionId,
                processedUrlCount = processedUrlCount,
                relevantCount = relevantCount,
                isGoodEnough = isGoodEnough,
                reason = reason
            ),
            eventChannel
        )
    }

    /**
     * Emit SYNTHESIS_COMPLETE event.
     */
    private suspend fun emitSynthesisComplete(
        sessionId: QuerySessionId,
        iterationNumber: Int,
        status: String,
        sourceCount: Int,
        followUpQueries: List<String>,
        eventChannel: SendChannel<SearchEvent>
    ) {
        emitFlowEvent(
            SearchFlowEvent.SynthesisComplete(
                sessionId = sessionId,
                iterationNumber = iterationNumber,
                sourceCount = sourceCount,
                status = status,
                followUpQueries = followUpQueries
            ),
            eventChannel
        )
    }

    /**
     * Emit FOLLOW_UP_QUERY_GENERATED event.
     */
    private suspend fun emitFollowUpSearchStarted(
        sessionId: QuerySessionId,
        followUpQueries: List<String>,
        whatsMissing: String?,
        iterationNumber: Int,
        eventChannel: SendChannel<SearchEvent>
    ) {
        emitFlowEvent(
            SearchFlowEvent.FollowUpQueryGenerated(
                sessionId = sessionId,
                followUpQueries = followUpQueries,
                whatsMissing = whatsMissing,
                iterationNumber = iterationNumber
            ),
            eventChannel
        )
    }

    /**
     * Emit ANSWER_CHUNK event.
     */
    private suspend fun emitAnswerChunk(
        sessionId: QuerySessionId,
        chunk: String,
        eventChannel: SendChannel<SearchEvent>
    ) {
        emitFlowEvent(
            SearchFlowEvent.AnswerChunk(
                sessionId = sessionId,
                chunk = chunk
            ),
            eventChannel
        )
    }

    /**
     * Emit SESSION_ERROR event with optional categorization.
     */
    private suspend fun emitSessionError(
        sessionId: QuerySessionId,
        errorType: String,
        errorMessage: String,
        eventChannel: SendChannel<SearchEvent>,
        errorCategory: String? = null,
        affectedUrl: String? = null,
        technicalDetails: String? = null
    ) {
        emitFlowEvent(
            SearchFlowEvent.SessionError(
                sessionId = sessionId,
                errorType = errorType,
                errorMessage = errorMessage,
                errorCategory = errorCategory,
                affectedUrl = affectedUrl,
                technicalDetails = technicalDetails
            ),
            eventChannel
        )
    }

    /**
     * Emit SESSION_ERROR event from a categorized UrlProcessingException.
     */
    private suspend fun emitSessionErrorFromException(
        sessionId: QuerySessionId,
        exception: io.deepsearch.domain.exceptions.UrlProcessingException,
        eventChannel: SendChannel<SearchEvent>
    ) {
        val categorized = io.deepsearch.domain.exceptions.CategorizedError.from(exception)
        emitSessionError(
            sessionId = sessionId,
            errorType = categorized.errorCode,
            errorMessage = categorized.userMessage,
            eventChannel = eventChannel,
            errorCategory = categorized.category.name,
            affectedUrl = categorized.url,
            technicalDetails = categorized.technicalDetails
        )
    }

    /**
     * Emit DISCOVERY_SERP_COMPLETE event (timeline only, no SSE equivalent).
     */
    private suspend fun emitDiscoverySerperComplete(
        sessionId: QuerySessionId,
        query: String,
        linksFound: Int,
        durationMs: Long
    ) {
        // This event is for timeline only - no channel needed
        searchFlowEventService.emit(
            SearchFlowEvent.DiscoverySerpComplete(
                sessionId = sessionId,
                query = query,
                linksFound = linksFound,
                durationMs = durationMs
            )
        )
    }

}
