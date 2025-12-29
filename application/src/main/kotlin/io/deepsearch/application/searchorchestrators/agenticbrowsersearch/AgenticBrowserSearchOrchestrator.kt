package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.application.services.IHtmlPreviewService
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.application.services.IUrlContentProcessingService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.application.services.SearchEvent
import io.deepsearch.application.services.WebpageCacheService
import io.deepsearch.domain.services.IGeminiFileSearchService
import io.deepsearch.domain.agents.IAnswerReviewerAgent
import io.deepsearch.domain.agents.IPreviewSourceShortlistAgent
import io.deepsearch.domain.agents.IQueryBreakdownAgent
import io.deepsearch.domain.agents.ISerpQueryOptimizationAgent
import io.deepsearch.domain.agents.ISourceShortlistAgent
import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.PreviewSourceShortlistInput
import io.deepsearch.domain.agents.SerpQueryOptimizationInput
import io.deepsearch.domain.agents.SourceShortlistInput
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.agents.StreamingAnswerStreamItem
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.exceptions.MarkdownConversionException
import io.deepsearch.domain.exceptions.NetworkConnectionException
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.deepsearch.domain.ratelimit.IAdaptiveRateLimiter
import io.deepsearch.domain.ext.chunkedWithTimeout
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.UrlContentResult
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.domain.models.valueobjects.SearchMode
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.models.valueobjects.UncachedUrlAccess
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.services.INormalizeUrlService
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface IAgenticBrowserSearchOrchestrator : ISearchOrchestrator

/**
 * Orchestrates agentic search using a reactive flow-based approach.
 * Returns a Flow<SearchEvent> that emits events as the search progresses.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class AgenticBrowserSearchOrchestrator(
    private val applicationScope: IApplicationCoroutineScope,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val normalizeUrlService: INormalizeUrlService,
    private val serpQueryOptimizationAgent: ISerpQueryOptimizationAgent,
    private val queryBreakdownAgent: IQueryBreakdownAgent,
    private val urlContentProcessingService: IUrlContentProcessingService,
    private val streamingAnswerAgent: IStreamingAnswerAgent,
    private val answerReviewerAgent: IAnswerReviewerAgent,
    private val sourceShortlistAgent: ISourceShortlistAgent,
    private val streamingAnswerSynthesisAgent: IStreamingAnswerSynthesisAgent,
    private val previewSourceShortlistAgent: IPreviewSourceShortlistAgent,
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService,
    private val webpageCacheService: WebpageCacheService,
    private val geminiFileSearchService: IGeminiFileSearchService,
    private val dispatchers: IDispatcherProvider,
    private val tokenUsageService: io.deepsearch.application.services.ILlmTokenUsageService,
    private val adaptiveRateLimiter: IAdaptiveRateLimiter,
    private val htmlPreviewService: IHtmlPreviewService
) : IAgenticBrowserSearchOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun execute(
        searchQuery: SearchQuery,
        maxCacheAge: Long?,
        apiKeyId: ApiKeyId,
        proxyConfig: ProxyConfiguration
    ): Flow<SearchEvent> = channelFlow {
        val session = querySessionService.createSession(
            searchQuery.query,
            searchQuery.url,
            apiKeyId,
            SearchMode.LIVE_CRAWLING
        )
        val sessionId = session.id

        try {
            // Emit session created
            send(
                SearchEvent.SessionCreated(
                    sessionId = sessionId,
                    query = searchQuery.query,
                    url = searchQuery.url,
                    mode = "live-crawling"
                )
            )

            val budget = SearchBudget(timeLimitMs = 300 * 1000L, maxLinks = 100)
            logger.debug("[{}] Executing search for query: {}", sessionId.value, searchQuery.query)

            val seenUrls = ConcurrentHashMap.newKeySet<String>()

            // Channels for discovered links
            val initialDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
            val serperSearchDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
            val hybridSearchDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
            val fileSearchDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
            val recursiveDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)

            // Flows that don't require query optimization - start immediately
            val immediateFlows = merge(
                processInitialLinkFlow(
                    sessionId,
                    searchQuery,
                    seenUrls,
                    initialDiscoveredLinksChannel,
                    maxCacheAge,
                    proxyConfig,
                    channel
                ),
                processSerperSearchLinksFlow(
                    sessionId,
                    searchQuery,
                    seenUrls,
                    serperSearchDiscoveredLinksChannel,
                    maxCacheAge,
                    proxyConfig,
                    channel
                ),
                processHybridSearchFlow(
                    sessionId,
                    searchQuery,
                    seenUrls,
                    maxCacheAge,
                    proxyConfig,
                    hybridSearchDiscoveredLinksChannel,
                    channel
                ),
                processFileSearchFlow(
                    sessionId,
                    searchQuery,
                    seenUrls,
                    maxCacheAge,
                    fileSearchDiscoveredLinksChannel,
                    channel
                ),
                processRecursiveDiscoveredLinksFlow(
                    sessionId, searchQuery, seenUrls, budget,
                    initialDiscoveredLinksChannel, serperSearchDiscoveredLinksChannel,
                    hybridSearchDiscoveredLinksChannel, fileSearchDiscoveredLinksChannel,
                    recursiveDiscoveredLinksChannel,
                    maxCacheAge, proxyConfig, channel
                )
            )

            // Create a child job for source processing that can be cancelled
            // when either path produces a confident answer
            val sourceProcessingJob = Job(coroutineContext[Job])
            val sourceProcessingScope = CoroutineScope(coroutineContext + sourceProcessingJob)

            // Share the source flow so both preview and main paths can consume it
            // Uses sourceProcessingScope so cancelling sourceProcessingJob cancels all in-flight work
            val sourceFlow = merge(immediateFlows)
                .cancellable()
                .shareIn(sourceProcessingScope, SharingStarted.Eagerly)

            // Track state for both paths
            var lastPreviewResult: PreviewResult? = null
            var answerAccumulator = AnswerAccumulator()

            // Ensure exactly-once session completion across concurrent paths
            val sessionCompleted = AtomicBoolean(false)

            // When includeImages is enabled, skip preview path and use stricter criteria
            val includeImages = searchQuery.includeImages
            if (includeImages) {
                logger.info("[{}] includeImages enabled, skipping preview path", sessionId.value)
            }

            // Build the list of flows to merge
            val pathFlows = mutableListOf<Flow<AccumulatorUpdate>>()

            // Preview path: fast HTML evaluation for early exit (stateless per batch)
            // Only enabled when includeImages is false
            if (!includeImages) {
                pathFlows.add(
                    sourceFlow
                        .filterIsInstance<UrlContentResult.HtmlPreview>()
                        .chunkedWithTimeout(chunkSize = 5, timeoutMs = 300)
                        .takeWhile { !answerAccumulator.isComplete }
                        .flatMapMerge(concurrency = 3) { htmlBatch ->
                            flow {
                                emit(processPreviewBatch(sessionId, searchQuery, htmlBatch))
                            }
                        }
                        .onEach { lastPreviewResult = it }
                        .filter { it.isAnswerFound }
                        .take(1)
                        .onEach { confidentResult ->
                            if (sessionCompleted.compareAndSet(false, true)) {
                                logger.info("[{}] Preview path produced confident answer, cancelling source processing", sessionId.value)
                                // Emit the buffered answer chunk (only the winning batch emits)
                                confidentResult.fullAnswer?.let { answer ->
                                    if (answer.isNotBlank()) {
                                        channel.send(SearchEvent.AnswerChunk(sessionId, answer))
                                    }
                                }
                                sourceProcessingJob.cancel()
                                finishWithPreviewAnswer(sessionId, confidentResult, channel)
                            }
                        }
                        .map { AccumulatorUpdate.Preview(it) }
                )
            }

            // Main path: full markdown processing
            pathFlows.add(
                sourceFlow
                    .filterIsInstance<UrlContentResult.FullMarkdown>()
                    .filter { it.markdown.isNotBlank() }
                    .map { MarkdownSource(it.url, it.title, it.description, it.markdown) }
                    .chunkedWithTimeout(chunkSize = 15, timeoutMs = 1000)
                    .takeWhile { !includeImages || lastPreviewResult?.isAnswerFound != true }
                    .runningFold(AnswerAccumulator()) { state, markdownResults ->
                        aggregateMarkdownResultIntoAnswer(
                            sessionId, searchQuery, state, markdownResults, channel,
                            isPreviewConfident = { !includeImages && lastPreviewResult?.isAnswerFound == true },
                            includeImages = includeImages
                        )
                    }
                    .onEach { answerAccumulator = it }
                    .filter { it.isComplete }
                    .take(1)
                    .onEach { completedAccumulator ->
                        if (sessionCompleted.compareAndSet(false, true)) {
                            logger.info("[{}] Main path completed, cancelling source processing", sessionId.value)
                            sourceProcessingJob.cancel()
                            finishQuerySession(sessionId, searchQuery, completedAccumulator, budget, channel)
                        }
                    }
                    .map { AccumulatorUpdate.Main(it) }
            )

            // Merge paths
            merge(*pathFlows.toTypedArray())
                .onCompletion {
                    // Handle case where neither path completed via onEach (sources exhausted or interrupted)
                    if (sessionCompleted.compareAndSet(false, true)) {
                        logger.info("[{}] Flow completed, cancelling source processing", sessionId.value)
                        sourceProcessingJob.cancel()
                        val confidentPreview = if (!includeImages) lastPreviewResult?.takeIf { it.isAnswerFound } else null
                        if (confidentPreview != null) {
                            logger.info("[{}] Preview path was confident at flow completion", sessionId.value)
                            finishWithPreviewAnswer(sessionId, confidentPreview, channel)
                        } else {
                            // Fall back to main path accumulator (may be incomplete)
                            finishQuerySession(sessionId, searchQuery, answerAccumulator, budget, channel)
                        }
                    }
                }
                .collect()
        } catch (e: CancellationException) {
            logger.debug("[{}] Flow cancelled", sessionId.value)
        } catch (e: Exception) {
            logger.error("[{}] Error in execute: {}", sessionId.value, e.message, e)
            querySessionService.hardTimeout(sessionId, e.message ?: "Unknown error")
            send(
                SearchEvent.SessionError(
                    sessionId = sessionId,
                    errorType = e::class.simpleName ?: "Unknown",
                    errorMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    /**
     * Process the initial user-provided URL.
     */
    private fun processInitialLinkFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        initialDiscoveredLinksChannel: Channel<WebpageLink>,
        maxCacheAge: Long?,
        proxyConfig: ProxyConfiguration,
        eventChannel: SendChannel<SearchEvent>
    ): Flow<UrlContentResult> {
        return flowOf(searchQuery.url)
            .flatMapMerge { url ->
                val normalizedUrl = normalizeUrlService.normalize(url) ?: url
                // Add URL to seenUrls before processing to prevent duplicate processing
                // This must happen before processUrlAsFlow, not on each event, because
                // multiple events (HtmlPreview, LinkDiscovery, MarkdownExtraction)
                // share the same URL and would filter out subsequent events
                seenUrls.add(normalizedUrl)
                eventChannel.send(SearchEvent.UrlProcessingStarted(sessionId, normalizedUrl))

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
                                event.discoveredLinks.forEach { initialDiscoveredLinksChannel.send(it) }
                                initialDiscoveredLinksChannel.close()
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
                                eventChannel.send(
                                    SearchEvent.UrlProcessed(
                                        sessionId = sessionId,
                                        url = event.url,
                                        accessType = if (event.wasCached) "CACHED" else "UNCACHED",
                                        title = event.title,
                                        description = event.description,
                                        markdownLength = event.markdown.length
                                    )
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
                        }
                    }
                    .filter { event ->
                        event is IUrlContentProcessingService.UrlProcessingEvent.HtmlPreviewReady ||
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

                            is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete ->
                                UrlContentResult.FullMarkdown(event.url, event.title, event.description, event.markdown)

                            else -> throw IllegalStateException("Unexpected event type")
                        }
                    }
                    .onCompletion { initialDiscoveredLinksChannel.close() }
            }
    }

    /**
     * Process discovered links from SERP search.
     */
    private fun processSerperSearchLinksFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        discoveredLinksChannel: Channel<WebpageLink>,
        maxCacheAge: Long?,
        proxyConfig: ProxyConfiguration,
        eventChannel: SendChannel<SearchEvent>
    ): Flow<UrlContentResult> {
        return processDiscoveredLinksFlow(
            sessionId, searchQuery, seenUrls,
            createSerperSearchLinkDiscoveryFlow(sessionId, searchQuery),
            discoveredLinksChannel, maxCacheAge, proxyConfig, eventChannel
        )
    }

    /**
     * Common processing logic for discovered links.
     * Uses adaptive rate limiting per domain to respect website rate limits.
     */
    private fun processDiscoveredLinksFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        linkSource: Flow<WebpageLink>,
        discoveredLinksChannel: Channel<WebpageLink>,
        maxCacheAge: Long?,
        proxyConfig: ProxyConfiguration,
        eventChannel: SendChannel<SearchEvent>
    ): Flow<UrlContentResult> {
        return linkSource
            .filter { link ->
                val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url
                // Apply language filter if configured
                val languagePattern = searchQuery.parsedLanguagePattern
                if (languagePattern != null && !languagePattern.matches(normalizedUrl, searchQuery.url)) {
                    logger.debug("[{}] Link filtered by language pattern: {}", sessionId.value, normalizedUrl)
                    return@filter false
                }
                // Use atomic add() which returns true only if element was NOT already present
                seenUrls.add(normalizedUrl)
            }
            .flatMapMerge(concurrency = 100) { link ->
                flow {
                    val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url
                    eventChannel.send(SearchEvent.UrlProcessingStarted(sessionId, normalizedUrl))

                    // Use adaptive rate limiter to respect website rate limits
                    adaptiveRateLimiter.withRateLimit(normalizedUrl) {
                        urlContentProcessingService.processUrlAsFlow(
                            normalizedUrl,
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
                                        eventChannel.send(
                                            SearchEvent.UrlProcessed(
                                                sessionId,
                                                e.url,
                                                "FAILED",
                                                errorMessage = e.reason
                                            )
                                        )
                                    }

                                    else -> throw e
                                }
                            }
                            .onEach { event ->
                                when (event) {
                                    is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                        event.discoveredLinks.forEach { discoveredLinksChannel.send(it) }
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
                                        eventChannel.send(
                                            SearchEvent.UrlProcessed(
                                                sessionId = sessionId,
                                                url = event.url,
                                                accessType = if (event.wasCached) "CACHED" else "UNCACHED",
                                                title = event.title,
                                                description = event.description,
                                                markdownLength = event.markdown.length
                                            )
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
                                }
                            }
                            .filter { event ->
                                event is IUrlContentProcessingService.UrlProcessingEvent.HtmlPreviewReady ||
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

                                    is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete ->
                                        UrlContentResult.FullMarkdown(
                                            event.url,
                                            event.title,
                                            event.description,
                                            event.markdown
                                        )

                                    else -> throw IllegalStateException("Unexpected event type")
                                }
                            }
                            .collect { emit(it) }
                    }
                }
            }
            .onCompletion { discoveredLinksChannel.close() }
    }

    /**
     * Process recursively discovered links with adaptive rate limiting.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun processRecursiveDiscoveredLinksFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        budget: SearchBudget,
        initialChannel: Channel<WebpageLink>,
        serperChannel: Channel<WebpageLink>,
        hybridChannel: Channel<WebpageLink>,
        fileSearchChannel: Channel<WebpageLink>,
        recursiveChannel: Channel<WebpageLink>,
        maxCacheAge: Long?,
        proxyConfig: ProxyConfiguration,
        eventChannel: SendChannel<SearchEvent>
    ): Flow<UrlContentResult> {
        val inFlight = ConcurrentHashMap.newKeySet<String>()

        return merge(
            merge(
                initialChannel.receiveAsFlow(),
                serperChannel.receiveAsFlow(),
                hybridChannel.receiveAsFlow(),
                fileSearchChannel.receiveAsFlow()
            )
                .onCompletion { if (inFlight.isEmpty()) recursiveChannel.close() },
            recursiveChannel.receiveAsFlow()
        )
            .takeWhile { !querySessionService.isBudgetExceeded(sessionId, budget) }
            .filter { link ->
                val url = normalizeUrlService.normalize(link.url) ?: link.url
                // Apply language filter if configured
                val languagePattern = searchQuery.parsedLanguagePattern
                if (languagePattern != null && !languagePattern.matches(url, searchQuery.url)) {
                    logger.debug("[{}] Link filtered by language pattern: {}", sessionId.value, url)
                    return@filter false
                }
                // Use atomic add() which returns true only if element was NOT already present
                seenUrls.add(url)
            }
            .flatMapMerge(concurrency = 100) { link ->
                flow {
                    val url = normalizeUrlService.normalize(link.url) ?: link.url
                    inFlight.add(url)
                    eventChannel.send(SearchEvent.UrlProcessingStarted(sessionId, url))

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
                                        eventChannel.send(
                                            SearchEvent.UrlProcessed(
                                                sessionId,
                                                e.url,
                                                "FAILED",
                                                errorMessage = e.reason
                                            )
                                        )
                                        if (initialChannel.isClosedForSend && serperChannel.isClosedForSend && hybridChannel.isClosedForSend && fileSearchChannel.isClosedForSend && inFlight.isEmpty()) {
                                            recursiveChannel.close()
                                        }
                                    }

                                    else -> throw e
                                }
                            }
                            .onEach { event ->
                                when (event) {
                                    is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                        inFlight.remove(event.url)
                                        val languagePattern = searchQuery.parsedLanguagePattern
                                        val newLinks = event.discoveredLinks.filter { discoveredLink ->
                                            val discoveredUrl =
                                                normalizeUrlService.normalize(discoveredLink.url) ?: discoveredLink.url
                                            // Check language filter
                                            val matchesLanguage = languagePattern == null ||
                                                    languagePattern.matches(discoveredUrl, searchQuery.url)
                                            matchesLanguage && !seenUrls.contains(discoveredUrl)
                                        }
                                        newLinks.forEach { recursiveChannel.send(it) }
                                        if (initialChannel.isClosedForSend && serperChannel.isClosedForSend && hybridChannel.isClosedForSend && fileSearchChannel.isClosedForSend && inFlight.isEmpty() && newLinks.isEmpty()) {
                                            recursiveChannel.close()
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
                                        eventChannel.send(
                                            SearchEvent.UrlProcessed(
                                                sessionId = sessionId,
                                                url = event.url,
                                                accessType = if (event.wasCached) "CACHED" else "UNCACHED",
                                                title = event.title,
                                                description = event.description,
                                                markdownLength = event.markdown.length
                                            )
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
                                }
                            }
                            .filter { event ->
                                event is IUrlContentProcessingService.UrlProcessingEvent.HtmlPreviewReady ||
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

                                    is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete ->
                                        UrlContentResult.FullMarkdown(
                                            event.url,
                                            event.title,
                                            event.description,
                                            event.markdown
                                        )

                                    else -> throw IllegalStateException("Unexpected event type")
                                }
                            }
                            .collect { emit(it) }
                    }
                }
            }
    }

    /**
     * Process hybrid search using cached webpages.
     */
    private fun processHybridSearchFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        maxCacheAge: Long?,
        proxyConfig: ProxyConfiguration,
        hybridChannel: Channel<WebpageLink>,
        eventChannel: SendChannel<SearchEvent>
    ): Flow<UrlContentResult> = flow {
        val webpages =
            webpageCacheService.searchHybrid(searchQuery.query, searchQuery.url, maxCacheAge, 15, sessionId)
                .filter { !it.markdown.isNullOrBlank() && !it.html.isNullOrBlank() }

        seenUrls.addAll(webpages.map { it.url })

        // Partition webpages by preview status
        val (previewPages, fullPages) = webpages.partition { it.isPreview }

        // Emit full markdown pages - emit preview first for preview path, then full markdown
        fullPages.forEach { webpage ->
            eventChannel.send(SearchEvent.UrlProcessingStarted(sessionId, webpage.url))
            urlAccessService.recordUrlAccess(sessionId, CachedUrlAccess(webpage.url, Clock.System.now()))
            
            // Emit HtmlPreview for preview path evaluation
            // Must clean cached HTML through HtmlPreviewService for consistency with live crawl path
            val previewResult = htmlPreviewService.prepareHtmlPreview(webpage.html!!, webpage.url)
            emit(UrlContentResult.HtmlPreview(
                webpage.url, 
                previewResult.title ?: webpage.title, 
                previewResult.description ?: webpage.description, 
                previewResult.cleanedHtml
            ))
            
            // Emit UrlProcessed and FullMarkdown for main path
            eventChannel.send(
                SearchEvent.UrlProcessed(
                    sessionId = sessionId,
                    url = webpage.url,
                    accessType = "CACHED",
                    title = webpage.title,
                    description = webpage.description,
                    markdownLength = webpage.markdown?.length
                )
            )
            emit(UrlContentResult.FullMarkdown(webpage.url, webpage.title, webpage.description, webpage.markdown!!))
        }

        // For preview pages: emit HtmlPreview for preview path (no SSE events), then trigger full extraction
        previewPages.forEach { webpage ->
            urlAccessService.recordUrlAccess(sessionId, CachedUrlAccess(webpage.url, Clock.System.now()))
            // Must clean cached HTML through HtmlPreviewService for consistency with live crawl path
            val previewResult = htmlPreviewService.prepareHtmlPreview(webpage.html!!, webpage.url)
            emit(UrlContentResult.HtmlPreview(
                webpage.url, 
                previewResult.title ?: webpage.title, 
                previewResult.description ?: webpage.description, 
                previewResult.cleanedHtml
            ))
        }

        // Process preview pages through full extraction
        previewPages.asFlow()
            .flatMapMerge(concurrency = 15) { webpage ->
                // Emit UrlProcessingStarted before full extraction
                eventChannel.send(SearchEvent.UrlProcessingStarted(sessionId, webpage.url))

                urlContentProcessingService.processUrlAsFlow(
                    webpage.url,
                    searchQuery.query,
                    maxCacheAge,
                    sessionId,
                    searchQuery.ocrLanguage,
                    proxyConfig
                )
                    .catch { e ->
                        when (e) {
                            is CancellationException -> throw e
                            else -> logger.warn(
                                "[{}] Hybrid search full extraction failed for {}: {}",
                                sessionId.value,
                                webpage.url,
                                e.message
                            )
                        }
                    }
                    .filter { event ->
                        event is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete
                    }
                    .onEach { event ->
                        when (event) {
                            is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                // Emit UrlProcessed when full markdown is ready
                                eventChannel.send(
                                    SearchEvent.UrlProcessed(
                                        sessionId = sessionId,
                                        url = event.url,
                                        accessType = "CACHED",
                                        title = event.title,
                                        description = event.description,
                                        markdownLength = event.markdown.length
                                    )
                                )
                            }

                            else -> {} // Shouldn't happen due to filter
                        }
                    }
                    .map { event ->
                        when (event) {
                            is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete ->
                                UrlContentResult.FullMarkdown(event.url, event.title, event.description, event.markdown)

                            else -> throw IllegalStateException("Unexpected event type")
                        }
                    }
            }
            .collect { emit(it) }

        // Discover links from all cached HTML (both full and preview pages)
        webpages.asFlow()
            .flatMapMerge(concurrency = 15) { webpage ->
                flow<Unit> {
                    try {
                        val links = webpageLinkDiscoveryService.discoverRelevantLinksByAgent(
                            searchQuery.query,
                            webpage.html!!,
                            webpage.url,
                            sessionId
                        )
                        links.forEach { hybridChannel.send(it) }
                    } catch (e: Exception) {
                        logger.warn("[{}] Hybrid search failed for {}: {}", sessionId.value, webpage.url, e.message)
                    }
                }
            }
            .onCompletion { hybridChannel.close() }
            .collect()
    }

    /**
     * Process file search using Gemini File Search stores.
     * Queries the file search store for the target domain and returns relevant content
     * from indexed files (PDFs, documents, etc.).
     * Also discovers links from chunk content and emits them for recursive crawling.
     */
    private fun processFileSearchFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        maxCacheAge: Long?,
        fileSearchChannel: Channel<WebpageLink>,
        eventChannel: SendChannel<SearchEvent>
    ): Flow<UrlContentResult> = flow {
        try {
            // Extract domain from URL
            val domain = extractDomain(searchQuery.url)
            logger.debug("[{}] Looking for file search store for domain: {}", sessionId.value, domain)

            // Only query if a store already exists for this domain
            val storeInfo = geminiFileSearchService.findStore(domain)
            if (storeInfo == null) {
                logger.debug(
                    "[{}] No file search store exists for domain: {}, skipping file search",
                    sessionId.value,
                    domain
                )
                fileSearchChannel.close()
                return@flow
            }

            // Query the file search store
            val searchResult = geminiFileSearchService.queryStore(
                storeName = storeInfo.name,
                query = searchQuery.query,
                maxAgeMs = maxCacheAge
            )

            // Record token usage
            tokenUsageService.recordTokenUsage(
                sessionId, "FileSearchFlow.queryStore",
                searchResult.tokenUsage.modelName, searchResult.tokenUsage.promptTokens,
                searchResult.tokenUsage.outputTokens, searchResult.tokenUsage.totalTokens
            )

            logger.debug("[{}] File search returned {} chunks", sessionId.value, searchResult.chunks.size)

            // Convert chunks to MarkdownSource and emit
            searchResult.chunks.forEach { chunk ->
                if (chunk.content.isNotBlank()) {
                    seenUrls.add(chunk.sourceUrl)

                    eventChannel.send(
                        SearchEvent.UrlProcessed(
                            sessionId = sessionId,
                            url = chunk.sourceUrl,
                            accessType = "FILE_SEARCH",
                            title = chunk.fileName,
                            description = "Retrieved from file search",
                            markdownLength = chunk.content.length
                        )
                    )

                    emit(
                        UrlContentResult.FullMarkdown(
                            url = chunk.sourceUrl,
                            title = chunk.fileName,
                            description = "Retrieved from file search",
                            markdown = chunk.content
                        )
                    )
                }
            }

            // Discover relevant links from chunk content using LLM (similar to processHybridSearchFlow)
            searchResult.chunks.asFlow()
                .flatMapMerge(concurrency = 20) { chunk ->
                    flow<Unit> {
                        try {
                            val links = webpageLinkDiscoveryService.discoverRelevantLinksFromText(
                                query = searchQuery.query,
                                text = chunk.content,
                                sourceUrl = searchQuery.url,
                                sessionId = sessionId
                            )
                            links.filter { it.url !in seenUrls }.forEach { link ->
                                fileSearchChannel.send(link)
                            }
                        } catch (e: Exception) {
                            logger.warn("[{}] File search link discovery failed: {}", sessionId.value, e.message)
                        }
                    }
                }
                .onCompletion { fileSearchChannel.close() }
                .collect()
        } catch (e: Exception) {
            logger.warn("[{}] File search flow failed: {}", sessionId.value, e.message)
            fileSearchChannel.close()
        }
    }

    private fun extractDomain(url: String): String {
        return try {
            java.net.URI(url).host?.lowercase() ?: url
        } catch (e: Exception) {
            url
        }
    }

    /**
     * Creates a flow that discovers links via SERP search.
     */
    private fun createSerperSearchLinkDiscoveryFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery
    ): Flow<WebpageLink> = flow {
        try {
            webpageLinkDiscoveryService.discoverRelevantLinksBySerper(searchQuery).forEach { emit(it) }
        } catch (e: Exception) {
            logger.error("[{}] SERP search failed: {}", sessionId.value, e.message)
        }
    }

    /**
     * Optimizes the query for SERP search if it's long (> 10 words).
     * Short queries are already well-suited for Google, while longer natural language
     * queries benefit from optimization to extract key terms.
     */
    private suspend fun optimizeQueryForSerp(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery
    ): SearchQuery {
        val wordCount = searchQuery.query.trim().split("\\s+".toRegex()).size

        logger.debug("[{}] Query has {} words, optimizing for SERP", sessionId.value, wordCount)

        val output = serpQueryOptimizationAgent.generate(
            SerpQueryOptimizationInput(
                query = searchQuery.query,
                targetUrl = searchQuery.url
            )
        )

        tokenUsageService.recordTokenUsage(
            sessionId, "SerpQueryOptimizationAgent",
            output.tokenUsage.modelName, output.tokenUsage.promptTokens,
            output.tokenUsage.outputTokens, output.tokenUsage.totalTokens
        )

        logger.debug("[{}] Optimized query: '{}' -> '{}'", sessionId.value, searchQuery.query, output.optimizedQuery)

        return SearchQuery(output.optimizedQuery, searchQuery.url)
    }

    /**
     * Accumulator for streaming answer generation.
     * Tracks markdown sources by URL to enable preview-to-full-markdown upgrades.
     */
    private data class AnswerAccumulator(
        val currentShortlist: List<ShortlistedSource> = emptyList(),
        /** Set of URLs that have been processed (for deduplication) */
        val processedUrls: Set<String> = emptySet(),
        val isComplete: Boolean = false
    )

    /**
     * Result from processing a preview batch.
     * Stateless - each batch is processed independently.
     * Uses a 2-agent flow: PreviewSourceShortlistAgent -> StreamingAnswerSynthesisAgent.
     */
    private data class PreviewResult(
        val shortlistedSources: List<ShortlistedSource> = emptyList(),
        val isAnswerFound: Boolean = false,
        val fullAnswer: String? = null
    )

    /**
     * Sealed class to distinguish accumulator updates from different paths.
     */
    private sealed class AccumulatorUpdate {
        data class Preview(val result: PreviewResult) : AccumulatorUpdate()
        data class Main(val accumulator: AnswerAccumulator) : AccumulatorUpdate()
    }

    private suspend fun aggregateMarkdownResultIntoAnswer(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        state: AnswerAccumulator,
        markdownSources: List<MarkdownSource>,
        eventChannel: SendChannel<SearchEvent>,
        isPreviewConfident: () -> Boolean,
        includeImages: Boolean = false
    ): AnswerAccumulator {
        // Filter to only new URLs (deduplication)
        val sourcesToEvaluate = markdownSources.filter { it.url !in state.processedUrls }

        // If no new sources to evaluate, return current state unchanged
        if (sourcesToEvaluate.isEmpty()) {
            return state
        }

        val updatedProcessedUrls = state.processedUrls + sourcesToEvaluate.map { it.url }

        val output = sourceShortlistAgent.generate(
            SourceShortlistInput(searchQuery.query, state.currentShortlist, sourcesToEvaluate, includeImages)
        )

        tokenUsageService.recordTokenUsage(
            sessionId, "SourceShortlistAgent",
            output.tokenUsage.modelName, output.tokenUsage.promptTokens,
            output.tokenUsage.outputTokens, output.tokenUsage.totalTokens
        )

        // Only emit ShortlistUpdated if preview path hasn't already produced a confident answer
        // This prevents race condition where main path emits events after preview answer starts streaming
        if (!isPreviewConfident()) {
            eventChannel.send(
                SearchEvent.ShortlistUpdated(
                    sessionId = sessionId,
                    processedUrlCount = updatedProcessedUrls.size,
                    shortlistedCount = output.updatedShortlist.size,
                    isGoodEnough = output.isGoodEnough,
                    reason = output.reason
                )
            )
        }

        return AnswerAccumulator(output.updatedShortlist, updatedProcessedUrls, output.isGoodEnough)
    }

    private suspend fun finishQuerySession(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        accumulator: AnswerAccumulator,
        budget: SearchBudget,
        eventChannel: SendChannel<SearchEvent>
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

        // Stream the answer and emit chunks
        var fullAnswer = ""
        streamingAnswerSynthesisAgent.generateStream(
            StreamingAnswerSynthesisInput(searchQuery.query, accumulator.currentShortlist)
        ).collect { item ->
            when (item) {
                is StreamingAnswerStreamItem.Chunk -> {
                    fullAnswer += item.text
                    eventChannel.send(SearchEvent.AnswerChunk(sessionId, item.text))
                }

                is StreamingAnswerStreamItem.Complete -> {
                    // Record token usage from the final streaming chunk
                    tokenUsageService.recordTokenUsage(
                        sessionId, "StreamingAnswerSynthesisAgent",
                        item.tokenUsage.modelName, item.tokenUsage.promptTokens,
                        item.tokenUsage.outputTokens, item.tokenUsage.totalTokens
                    )
                    // Capture answerFound and image IDs from answer synthesis
                    val answerFound = item.answerFound
                    val imageIds = item.imageIds

                    val answerSources = accumulator.currentShortlist.map { it.url }
                    if (answerSources.isNotEmpty()) {
                        urlAccessService.markUrlsAsUsedInAnswer(sessionId, answerSources)
                    }

                    val finishReason = when {
                        accumulator.isComplete -> "ANSWER_COMPLETE"
                        querySessionService.isBudgetExceeded(sessionId, budget) -> "BUDGET_EXCEEDED"
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
                            budget,
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
                            imageIds = imageIds
                        )
                    )
                }
            }
        }
    }

    /**
     * Process a batch of HTML previews independently (stateless, side-effect-free).
     * Uses a 2-agent flow:
     * 1. PreviewSourceShortlistAgent (non-streaming) - extracts facts and classifies sources, filters table facts
     * 2. StreamingAnswerSynthesisAgent (streaming) - generates answer and determines answerFound
     * 
     * Returns a PreviewResult with buffered answer. The caller is responsible for emitting
     * the answer to the event channel after .take(1) selects the winning batch.
     */
    private suspend fun processPreviewBatch(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        htmlBatch: List<UrlContentResult.HtmlPreview>
    ): PreviewResult {
        if (htmlBatch.isEmpty()) {
            return PreviewResult()
        }

        // Step 1: Source Shortlist (non-streaming) - extracts facts, filters table facts
        val shortlistOutput = previewSourceShortlistAgent.generate(
            PreviewSourceShortlistInput(searchQuery.query, htmlBatch)
        )

        tokenUsageService.recordTokenUsage(
            sessionId, "PreviewSourceShortlistAgent",
            shortlistOutput.tokenUsage.modelName, shortlistOutput.tokenUsage.promptTokens,
            shortlistOutput.tokenUsage.outputTokens, shortlistOutput.tokenUsage.totalTokens
        )

        logger.debug(
            "[{}] Preview source shortlist: {} sources, {} facts",
            sessionId.value,
            shortlistOutput.shortlistedSources.size,
            shortlistOutput.shortlistedSources.sumOf { it.relevantFacts.size }
        )

        // Step 2: Answer Synthesis (streaming) - uses unified agent, determines answerFound
        val fullAnswer = StringBuilder()
        var answerFound = false
        var reasoning = ""

        streamingAnswerSynthesisAgent.generateStream(
            StreamingAnswerSynthesisInput(searchQuery.query, shortlistOutput.shortlistedSources)
        ).collect { item ->
            when (item) {
                is StreamingAnswerStreamItem.Chunk -> {
                    fullAnswer.append(item.text)
                    // Buffer chunks - only emit if answerFound=true
                }

                is StreamingAnswerStreamItem.Complete -> {
                    tokenUsageService.recordTokenUsage(
                        sessionId, "StreamingAnswerSynthesisAgent",
                        item.tokenUsage.modelName, item.tokenUsage.promptTokens,
                        item.tokenUsage.outputTokens, item.tokenUsage.totalTokens
                    )
                    answerFound = item.answerFound
                    reasoning = item.reasoning
                }
            }
        }

        logger.debug(
            "[{}] Preview answer synthesis: answerFound={}, answer length={}, reasoning={}",
            sessionId.value, answerFound, fullAnswer.length, reasoning
        )

        // Return result with buffered answer - emission happens after .take(1) selects the winner
        return PreviewResult(
            shortlistedSources = shortlistOutput.shortlistedSources,
            isAnswerFound = answerFound,
            fullAnswer = if (answerFound) fullAnswer.toString() else null
        )
    }

    /**
     * Finish the query session with a preview answer.
     * Called when the preview path produces a confident answer.
     * The answer has already been streamed by processPreviewBatch,
     * so this just completes the session.
     * Uses database as source of truth to check if session is already finished.
     */
    private suspend fun finishWithPreviewAnswer(
        sessionId: QuerySessionId,
        result: PreviewResult,
        eventChannel: SendChannel<SearchEvent>
    ) {
        // Check if session is already finished (use DB as source of truth)
        val session = querySessionService.getSession(sessionId)
        if (session.finishReason != null) {
            logger.debug(
                "[{}] Session already finished with {}, skipping preview answer",
                sessionId.value,
                session.finishReason
            )
            return
        }

        logger.info("[{}] Finishing with preview answer (early exit)", sessionId.value)

        // Emit shortlist update for preview path (so frontend knows about preview sources)
        eventChannel.send(
            SearchEvent.ShortlistUpdated(
                sessionId = sessionId,
                processedUrlCount = result.shortlistedSources.size,
                shortlistedCount = result.shortlistedSources.size,
                isGoodEnough = true, // Preview path only finishes when confident
                reason = "Preview path confident answer"
            )
        )

        val answerSources = result.shortlistedSources.map { it.url }
        if (answerSources.isNotEmpty()) {
            urlAccessService.markUrlsAsUsedInAnswer(sessionId, answerSources)
        }

        querySessionService.completeSessionPreviewAnswerComplete(
            sessionId,
            result.fullAnswer ?: "",
            result.fullAnswer?.isNotBlank() == true
        )

        val sessionDetail = querySessionService.getSessionDetailInternal(sessionId)

        eventChannel.send(
            SearchEvent.SessionCompleted(
                sessionId = sessionId,
                finishReason = "PREVIEW_ANSWER_COMPLETE",
                sessionDetail = sessionDetail,
                imageIds = emptyList()
            )
        )
    }
}
