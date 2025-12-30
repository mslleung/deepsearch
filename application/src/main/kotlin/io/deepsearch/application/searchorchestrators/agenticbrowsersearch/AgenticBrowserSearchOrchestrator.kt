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
import io.deepsearch.domain.agents.IHtmlSourceEvalAgent
import io.deepsearch.domain.agents.IMarkdownSourceEvalAgent
import io.deepsearch.domain.agents.IQueryBreakdownAgent
import io.deepsearch.domain.agents.ISerpQueryOptimizationAgent
import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.HtmlSourceEvalInput
import io.deepsearch.domain.agents.MarkdownSourceEvalInput
import io.deepsearch.domain.agents.SerpQueryOptimizationInput
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.agents.StreamingAnswerStreamItem
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.exceptions.MarkdownConversionException
import io.deepsearch.domain.exceptions.NetworkConnectionException
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.deepsearch.domain.ratelimit.IAdaptiveRateLimiter
import io.deepsearch.domain.ext.chunkedWithTimeout
import io.deepsearch.domain.models.valueobjects.AnswerType
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.UrlContentResult
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.domain.models.valueobjects.SearchMode
import io.deepsearch.domain.models.valueobjects.SearchQuery
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
    private val htmlSourceEvalAgent: IHtmlSourceEvalAgent,
    private val markdownSourceEvalAgent: IMarkdownSourceEvalAgent,
    private val streamingAnswerSynthesisAgent: IStreamingAnswerSynthesisAgent,
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService,
    private val webpageCacheService: WebpageCacheService,
    private val geminiFileSearchService: IGeminiFileSearchService,
    private val dispatchers: IDispatcherProvider,
    private val tokenUsageService: io.deepsearch.application.services.ILlmTokenUsageService,
    private val adaptiveRateLimiter: IAdaptiveRateLimiter,
    private val htmlPreviewService: IHtmlPreviewService,
    private val kgHybridRetrievalService: io.deepsearch.application.services.IKgHybridRetrievalService
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
            val kgDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
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
                processKnowledgeGraphSearchFlow(
                    sessionId,
                    searchQuery,
                    seenUrls,
                    maxCacheAge,
                    kgDiscoveredLinksChannel,
                    channel
                ),
                processRecursiveDiscoveredLinksFlow(
                    sessionId, searchQuery, seenUrls, budget,
                    initialDiscoveredLinksChannel, serperSearchDiscoveredLinksChannel,
                    hybridSearchDiscoveredLinksChannel, fileSearchDiscoveredLinksChannel,
                    kgDiscoveredLinksChannel,
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

            // Preview path: fast HTML evaluation for early exit (stateless per source)
            // Only enabled when includeImages is false
            if (!includeImages) {
                pathFlows.add(
                    sourceFlow
                        .filterIsInstance<UrlContentResult.HtmlPreview>()
                        .takeWhile { !answerAccumulator.isComplete }
                        .flatMapMerge(concurrency = 100) { htmlSource ->
                            flow {
                                emit(processHtmlSource(sessionId, searchQuery, htmlSource))
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

            // Main path: full markdown processing (parallel evaluation, then answer synthesis)
            pathFlows.add(
                sourceFlow
                    .filterIsInstance<UrlContentResult.FullMarkdown>()
                    .filter { it.markdown.isNotBlank() }
                    .map { MarkdownSource(it.url, it.title, it.description, it.markdown) }
                    .takeWhile { !includeImages || lastPreviewResult?.isAnswerFound != true }
                    .flatMapMerge(concurrency = 100) { markdownSource ->
                        flow {
                            emit(evaluateMarkdownSource(sessionId, searchQuery, markdownSource))
                        }
                    }
                    .filter { it != null }
                    .map { it!! }
                    .runningFold(AnswerAccumulator()) { state, evalResult ->
                        aggregateMarkdownResultIntoAnswer(
                            sessionId, searchQuery, state, evalResult, channel,
                            isPreviewConfident = { !includeImages && lastPreviewResult?.isAnswerFound == true }
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
        kgChannel: Channel<WebpageLink>,
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
                fileSearchChannel.receiveAsFlow(),
                kgChannel.receiveAsFlow()
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

        // Emit full markdown pages in parallel - emit preview first for preview path, then full markdown
        fullPages.asFlow()
            .flatMapMerge(concurrency = 100) { webpage ->
                flow {
                    eventChannel.send(SearchEvent.UrlProcessingStarted(sessionId, webpage.url))
                    urlAccessService.recordUrlAccess(sessionId, CachedUrlAccess(webpage.url, Clock.System.now()))
                    
                    // Use pre-computed cleaned preview HTML from cache (computed when HTML was first cached)
                    emit(UrlContentResult.HtmlPreview(
                        webpage.url, 
                        webpage.title, 
                        webpage.description, 
                        webpage.cleanedPreviewHtml!!
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
            }
            .collect { emit(it) }

        // For preview pages: emit HtmlPreview in parallel for preview path (no SSE events), then trigger full extraction
        previewPages.asFlow()
            .flatMapMerge(concurrency = 100) { webpage ->
                flow {
                    urlAccessService.recordUrlAccess(sessionId, CachedUrlAccess(webpage.url, Clock.System.now()))
                    
                    // Use pre-computed cleaned preview HTML from cache (computed when HTML was first cached)
                    emit(UrlContentResult.HtmlPreview(
                        webpage.url, 
                        webpage.title, 
                        webpage.description, 
                        webpage.cleanedPreviewHtml!!
                    ))
                }
            }
            .collect { emit(it) }

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
     * Process Knowledge Graph for URL discovery.
     * 
     * Uses semantic entity matching + graph traversal to find relevant source URLs.
     * These URLs are emitted for processing through the normal crawl → Source Eval Agent flow,
     * which properly handles authority classification (OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, OTHERS)
     * and conflict resolution.
     * 
     * Note: KG is used ONLY for URL discovery, NOT for direct fact retrieval.
     * This ensures all facts go through the Source Eval Agent for proper authority ranking
     * and staleness handling.
     */
    private fun processKnowledgeGraphSearchFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        maxCacheAge: Long?,
        kgDiscoveredLinksChannel: Channel<WebpageLink>,
        @Suppress("UNUSED_PARAMETER") eventChannel: SendChannel<SearchEvent>
    ): Flow<UrlContentResult> = flow {
        try {
            // Check if KG has data for this URL prefix
            val urlPrefix = normalizeUrlService.normalize(searchQuery.url) ?: searchQuery.url
            if (!kgHybridRetrievalService.hasDataForUrlPrefix(urlPrefix)) {
                logger.debug("[{}] No KG data for URL prefix: {}", sessionId.value, urlPrefix)
                kgDiscoveredLinksChannel.close()
                return@flow
            }
            
            // Run semantic graph + text-to-cypher in parallel
            val kgResult = kgHybridRetrievalService.retrieve(
                query = searchQuery.query,
                baseUrl = urlPrefix,
                maxCacheAge = maxCacheAge,
                sessionId = sessionId
            )
            
            if (!kgResult.hasResults()) {
                logger.debug("[{}] KG retrieval returned no results", sessionId.value)
                kgDiscoveredLinksChannel.close()
                return@flow
            }
            
            logger.debug(
                "[{}] KG retrieval found {} entities - emitting source URLs for Source Eval Agent processing",
                sessionId.value,
                kgResult.subgraph?.entities?.size ?: 0
            )
            
            // Emit discovered URLs from KG subgraph as links for processing
            // These URLs will go through the normal crawl → Source Eval Agent flow
            // which handles authority classification and conflict resolution
            kgResult.subgraph?.entities?.forEach { entity ->
                entity.sourceUrls.forEach { url ->
                    if (seenUrls.add(url)) {
                        kgDiscoveredLinksChannel.send(
                            WebpageLink(url, LinkSource.KNOWLEDGE_GRAPH, "KG entity: ${entity.name}")
                        )
                    }
                }
            }
            
            // Note: We intentionally do NOT emit KG facts directly.
            // All facts should come from the Source Eval Agent which properly classifies
            // source authority and handles stale/conflicting information.
        } catch (e: Exception) {
            logger.warn("[{}] KG search failed: {}", sessionId.value, e.message)
        } finally {
            kgDiscoveredLinksChannel.close()
        }
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
        /** Evaluated sources accumulated from parallel markdown evaluation */
        val evaluatedSources: List<EvaluatedSource> = emptyList(),
        /** Set of URLs that have been processed (for deduplication) */
        val processedUrls: Set<String> = emptySet(),
        val isComplete: Boolean = false,
        /** Expanded query from the eval agent that clarifies the user's core intent */
        val expandedQuery: String? = null,
        /** Full answer from the last answer synthesis attempt */
        val fullAnswer: String? = null,
        /** URLs of sources that were actually cited in the answer */
        val citedSourceUrls: List<String> = emptyList(),
        /** Image IDs from the answer */
        val imageIds: List<String> = emptyList()
    )

    /**
     * Result from processing a preview source.
     * Stateless - each source is processed independently.
     * Uses a 2-agent flow: HtmlSourceEvalAgent -> StreamingAnswerSynthesisAgent.
     */
    private data class PreviewResult(
        val evaluatedSources: List<EvaluatedSource> = emptyList(),
        val answerType: AnswerType = AnswerType.PARTIAL_MENTION,
        val fullAnswer: String? = null
    ) {
        /**
         * Whether a confident answer was found.
         * Preview path only accepts DIRECT_ANSWER to avoid hallucination.
         * INFERRED_ANSWER and PARTIAL_MENTION are not confident enough for early exit.
         */
        val isAnswerFound: Boolean get() = answerType == AnswerType.DIRECT_ANSWER
    }

    /**
     * Sealed class to distinguish accumulator updates from different paths.
     */
    private sealed class AccumulatorUpdate {
        data class Preview(val result: PreviewResult) : AccumulatorUpdate()
        data class Main(val accumulator: AnswerAccumulator) : AccumulatorUpdate()
    }

    /**
     * Result from evaluating a single markdown source.
     */
    private data class MarkdownEvalResult(
        val evaluatedSource: EvaluatedSource,
        val expandedQuery: String
    )

    /**
     * Result from answer synthesis, containing all relevant data from the streaming response.
     */
    private data class SynthesisResult(
        val answer: String,
        val answerType: AnswerType,
        val reasoning: String,
        val citedSourceUrls: List<String>,
        val imageIds: List<String>
    ) {
        /** Whether the answer is good enough (DIRECT_ANSWER or INFERRED_ANSWER) */
        val isGoodEnough: Boolean get() = answerType != AnswerType.PARTIAL_MENTION
    }

    /**
     * Evaluate a single markdown source using the stateless MarkdownSourceEvalAgent.
     * Returns null if the source is not relevant.
     */
    private suspend fun evaluateMarkdownSource(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        markdownSource: MarkdownSource
    ): MarkdownEvalResult? {
        val output = markdownSourceEvalAgent.generate(
            MarkdownSourceEvalInput(searchQuery, markdownSource)
        )

        tokenUsageService.recordTokenUsage(
            sessionId, "MarkdownSourceEvalAgent",
            output.tokenUsage.modelName, output.tokenUsage.promptTokens,
            output.tokenUsage.outputTokens, output.tokenUsage.totalTokens
        )

        return output.evaluatedSource?.let { 
            MarkdownEvalResult(it, output.expandedQuery) 
        }
    }

    /**
     * Synthesize an answer from evaluated sources.
     * Handles streaming collection and token usage recording.
     */
    private suspend fun synthesizeAnswer(
        sessionId: QuerySessionId,
        query: String,
        evaluatedSources: List<EvaluatedSource>,
        expandedQuery: String?
    ): SynthesisResult {
        val answerBuilder = StringBuilder()
        lateinit var answerType: AnswerType
        var reasoning = ""
        var citedSourceUrls = emptyList<String>()
        var imageIds = emptyList<String>()

        streamingAnswerSynthesisAgent.generateStream(
            StreamingAnswerSynthesisInput(
                query = query,
                evaluatedSources = evaluatedSources,
                expandedQuery = expandedQuery
            )
        ).collect { item ->
            when (item) {
                is StreamingAnswerStreamItem.Chunk -> answerBuilder.append(item.text)
                is StreamingAnswerStreamItem.Complete -> {
                    tokenUsageService.recordTokenUsage(
                        sessionId, "StreamingAnswerSynthesisAgent",
                        item.tokenUsage.modelName, item.tokenUsage.promptTokens,
                        item.tokenUsage.outputTokens, item.tokenUsage.totalTokens
                    )
                    answerType = item.answerType
                    reasoning = item.reasoning
                    citedSourceUrls = item.citedSourceUrls
                    imageIds = item.imageIds
                }
            }
        }

        return SynthesisResult(
            answer = answerBuilder.toString(),
            answerType = answerType,
            reasoning = reasoning,
            citedSourceUrls = citedSourceUrls,
            imageIds = imageIds
        )
    }

    /**
     * Aggregate a newly evaluated markdown source and attempt answer synthesis.
     * Uses answerType to determine if the answer is "good enough":
     * - DIRECT_ANSWER or INFERRED_ANSWER = good enough, stop collecting
     * - PARTIAL_MENTION = keep collecting sources
     */
    private suspend fun aggregateMarkdownResultIntoAnswer(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        state: AnswerAccumulator,
        evalResult: MarkdownEvalResult,
        eventChannel: SendChannel<SearchEvent>,
        isPreviewConfident: () -> Boolean
    ): AnswerAccumulator {
        // Skip if already processed (deduplication)
        if (evalResult.evaluatedSource.url in state.processedUrls) {
            return state
        }

        val updatedSources = state.evaluatedSources + evalResult.evaluatedSource
        val updatedProcessedUrls = state.processedUrls + evalResult.evaluatedSource.url

        // Synthesize answer with accumulated sources
        val synthesis = synthesizeAnswer(
            sessionId, searchQuery.query, updatedSources, evalResult.expandedQuery
        )

        logger.debug(
            "[{}] Main path synthesis: {} sources, answerType={}, isGoodEnough={}, citedSources={}",
            sessionId.value, updatedSources.size, synthesis.answerType, synthesis.isGoodEnough, synthesis.citedSourceUrls.size
        )

        // Only emit SourcesEvaluated if preview path hasn't already produced a confident answer
        if (!isPreviewConfident()) {
            eventChannel.send(
                SearchEvent.SourcesEvaluated(
                    sessionId = sessionId,
                    processedUrlCount = updatedProcessedUrls.size,
                    relevantCount = updatedSources.size,
                    isGoodEnough = synthesis.isGoodEnough,
                    reason = if (synthesis.isGoodEnough) "Answer is comprehensive (${synthesis.answerType})" else "Collecting more sources"
                )
            )
        }

        return AnswerAccumulator(
            evaluatedSources = updatedSources,
            processedUrls = updatedProcessedUrls,
            isComplete = synthesis.isGoodEnough,
            expandedQuery = evalResult.expandedQuery,
            fullAnswer = synthesis.answer,
            citedSourceUrls = synthesis.citedSourceUrls,
            imageIds = synthesis.imageIds
        )
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

        val fullAnswer = accumulator.fullAnswer ?: "No information found to answer the query."
        val imageIds = accumulator.imageIds
        val answerFound = accumulator.fullAnswer?.isNotBlank() == true

        eventChannel.send(SearchEvent.AnswerChunk(sessionId, fullAnswer))
        finishSessionWithAnswer(sessionId, accumulator, budget, eventChannel, fullAnswer, answerFound, imageIds)
    }

    private suspend fun finishSessionWithAnswer(
        sessionId: QuerySessionId,
        accumulator: AnswerAccumulator,
        budget: SearchBudget,
        eventChannel: SendChannel<SearchEvent>,
        fullAnswer: String,
        answerFound: Boolean,
        imageIds: List<String>
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

    /**
     * Process a single HTML preview source independently (stateless, side-effect-free).
     * Uses a 2-agent flow:
     * 1. HtmlSourceEvalAgent (non-streaming) - extracts facts and classifies source, filters table facts
     * 2. StreamingAnswerSynthesisAgent (streaming) - generates answer and determines answerFound
     * 
     * Returns a PreviewResult with buffered answer. The caller is responsible for emitting
     * the answer to the event channel after .take(1) selects the winning source.
     */
    private suspend fun processHtmlSource(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        htmlSource: UrlContentResult.HtmlPreview
    ): PreviewResult {
        // Step 1: Source Eval - extracts facts, filters table facts
        val evalOutput = htmlSourceEvalAgent.generate(HtmlSourceEvalInput(searchQuery, htmlSource))
        tokenUsageService.recordTokenUsage(
            sessionId, "HtmlSourceEvalAgent",
            evalOutput.tokenUsage.modelName, evalOutput.tokenUsage.promptTokens,
            evalOutput.tokenUsage.outputTokens, evalOutput.tokenUsage.totalTokens
        )

        val evaluatedSource = evalOutput.evaluatedSource ?: run {
            logger.debug("[{}] HTML source not relevant: {}", sessionId.value, htmlSource.url)
            return PreviewResult()
        }

        logger.debug(
            "[{}] HTML source evaluation: url={}, {} facts",
            sessionId.value, htmlSource.url, evaluatedSource.relevantFacts.size
        )

        // Step 2: Answer Synthesis
        val synthesis = synthesizeAnswer(
            sessionId, searchQuery.query, listOf(evaluatedSource), evalOutput.expandedQuery
        )

        logger.debug(
            "[{}] Preview synthesis: answerType={}, {} chars, reasoning={}",
            sessionId.value, synthesis.answerType, synthesis.answer.length, synthesis.reasoning
        )

        return PreviewResult(
            evaluatedSources = listOf(evaluatedSource),
            answerType = synthesis.answerType,
            fullAnswer = if (synthesis.isGoodEnough) synthesis.answer else null
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

        // Emit sources evaluated update for preview path (so frontend knows about preview sources)
        eventChannel.send(
            SearchEvent.SourcesEvaluated(
                sessionId = sessionId,
                processedUrlCount = result.evaluatedSources.size,
                relevantCount = result.evaluatedSources.size,
                isGoodEnough = true, // Preview path only finishes when confident
                reason = "Preview path confident answer"
            )
        )

        val answerSources = result.evaluatedSources.map { it.url }
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
