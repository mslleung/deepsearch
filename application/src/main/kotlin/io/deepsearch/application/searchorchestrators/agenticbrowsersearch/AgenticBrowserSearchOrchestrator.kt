package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.application.services.IHtmlPreviewService
import io.deepsearch.application.services.IHtmlSourceEvalService
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.application.services.IUrlContentProcessingService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.application.services.SearchEvent
import io.deepsearch.application.services.WebpageCacheService
import io.deepsearch.domain.services.IGeminiFileSearchService
import io.deepsearch.domain.agents.IAnswerReviewerAgent
import io.deepsearch.domain.agents.IMarkdownSourceEvalAgent
import io.deepsearch.domain.agents.IQueryBreakdownAgent
import io.deepsearch.domain.agents.ISerpQueryOptimizationAgent
import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.IFollowUpQueryDedupAgent
import io.deepsearch.domain.agents.FollowUpQueryDedupInput
import io.deepsearch.domain.agents.HtmlSourceEvalInput
import io.deepsearch.domain.agents.MarkdownSourceEvalInput
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.agents.StreamingAnswerStreamItem
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.exceptions.MarkdownConversionException
import io.deepsearch.domain.exceptions.NetworkConnectionException
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.deepsearch.domain.ratelimit.IAdaptiveRateLimiter
import io.deepsearch.domain.models.valueobjects.AnswerStatus
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
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import io.deepsearch.application.services.FeedbackLoopReport
import io.deepsearch.domain.agents.AnswerAssessment
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
    private val htmlSourceEvalService: IHtmlSourceEvalService,
    private val markdownSourceEvalAgent: IMarkdownSourceEvalAgent,
    private val streamingAnswerSynthesisAgent: IStreamingAnswerSynthesisAgent,
    private val followUpQueryDedupAgent: IFollowUpQueryDedupAgent,
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

    companion object {
        // Default score for links from sources that don't provide scoring (SERPER, etc.)
        private const val DEFAULT_LINK_SCORE = 10
    }

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
            val recursiveDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)

            // Discovery flows - unified query-driven discovery
            // All queries (initial + follow-up) flow through the same pipeline:
            // queryChannel → full discovery (SERP + Hybrid + KG + File) → priorityLinkBuffer
            val discoveryFlows = merge(
                // Initial URL processing (user-provided URL, always runs once)
                processInitialLinkFlow(
                    sessionId,
                    searchQuery,
                    seenUrls,
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
                    channel
                )
            )
            
            // Link processing flow - pulls from priority buffer and processes URLs
            val linkProcessingFlow = processPriorityLinksFlow(
                sessionId,
                searchQuery,
                seenUrls,
                budget,
                priorityLinkBuffer,
                recursiveDiscoveredLinksChannel,
                maxCacheAge,
                proxyConfig,
                channel
            )

            // Create a child job for source processing that can be cancelled
            // when either path produces a confident answer
            val sourceProcessingJob = Job(coroutineContext[Job])
            val sourceProcessingScope = CoroutineScope(coroutineContext + sourceProcessingJob)

            // Share the source flow so both preview and main paths can consume it
            // Uses sourceProcessingScope so cancelling sourceProcessingJob cancels all in-flight work
            val sourceFlow = merge(discoveryFlows, linkProcessingFlow)
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
            // Emits all results so follow-up queries can be processed after merge
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
                    .runningFold(AnswerAccumulator(searchedQueries = listOf(searchQuery.query))) { state, evalResult ->
                        aggregateMarkdownResultIntoAnswer(
                            sessionId, searchQuery, state, evalResult, channel,
                            isPreviewConfident = { !includeImages && lastPreviewResult?.isAnswerFound == true }
                        )
                    }
                    .onEach { answerAccumulator = it }
                    .map { AccumulatorUpdate.Main(it) }
            )

            // Merge paths and process follow-up queries uniformly (concurrency=1 for dedup)
            merge(*pathFlows.toTypedArray())
                .flatMapConcat { update: AccumulatorUpdate ->
                    flow<AccumulatorUpdate> {
                        // Extract pending follow-up queries from either path
                        val (pendingQueries, status, iterationNumber) = when (update) {
                            is AccumulatorUpdate.Preview -> Triple(
                                update.result.pendingFollowUpQueries,
                                update.result.status,
                                0 // Preview doesn't track iteration
                            )
                            is AccumulatorUpdate.Main -> Triple(
                                update.accumulator.pendingFollowUpQueries,
                                update.accumulator.status,
                                update.accumulator.iterationNumber
                            )
                        }
                        
                        // Process pending follow-up queries through dedup agent
                        if (pendingQueries.isNotEmpty() && status == AnswerStatus.NEED_MORE_INFORMATION) {
                            val dedupResult = followUpQueryDedupAgent.generate(
                                FollowUpQueryDedupInput(
                                    candidateQueries = pendingQueries,
                                    previouslySearchedQueries = previouslySearchedQueries.toList(),
                                    originalQuery = searchQuery.query
                                )
                            )
                            
                            // Filter out queries we've already tracked (thread-safe add returns true if new)
                            val newQueries = dedupResult.dedupedQueries.filter { query ->
                                previouslySearchedQueries.add(query)
                            }
                            
                            if (newQueries.isNotEmpty()) {
                                logger.info(
                                    "[{}] Dedup: {} candidates -> {} after dedup -> {} new: {}",
                                    sessionId.value, pendingQueries.size,
                                    dedupResult.dedupedQueries.size, newQueries.size, newQueries
                                )
                                
                                channel.send(
                                    SearchEvent.FollowUpSearchStarted(
                                        sessionId = sessionId,
                                        followUpQueries = newQueries,
                                        whatsMissing = "Follow-up queries",
                                        iterationNumber = iterationNumber
                                    )
                                )
                                
                                // Send to query channel to trigger discovery
                                newQueries.forEach { query ->
                                    queryChannel.send(query)
                                }
                            }
                        }
                        
                        emit(update)
                    }
                }
                .onEach { update ->
                    // Handle session completion for either path
                    when (update) {
                        is AccumulatorUpdate.Preview -> {
                            if (update.result.isAnswerFound && sessionCompleted.compareAndSet(false, true)) {
                                logger.info("[{}] Preview path produced confident answer, cancelling source processing", sessionId.value)
                                // Emit the buffered answer chunk
                                update.result.fullAnswer?.let { answer ->
                                    if (answer.isNotBlank()) {
                                        channel.send(SearchEvent.AnswerChunk(sessionId, answer))
                                    }
                                }
                                sourceProcessingJob.cancel()
                                queryChannel.close()
                                priorityLinkBuffer.close()
                                finishWithPreviewAnswer(sessionId, update.result, channel)
                            }
                        }
                        is AccumulatorUpdate.Main -> {
                            if (update.accumulator.isComplete && sessionCompleted.compareAndSet(false, true)) {
                                logger.info("[{}] Main path completed with COMPLETE status, cancelling source processing", sessionId.value)
                                sourceProcessingJob.cancel()
                                queryChannel.close()
                                priorityLinkBuffer.close()
                                finishQuerySession(sessionId, searchQuery, update.accumulator, budget, channel)
                            }
                        }
                    }
                }
                .takeWhile { !sessionCompleted.get() }
                .onCompletion {
                    // Handle case where neither path completed (sources exhausted or interrupted)
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
        priorityLinkBuffer: PriorityLinkChannel,
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
                                // Send discovered links to priority buffer
                                event.discoveredLinks.forEach { priorityLinkBuffer.send(it) }
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
            }
    }

    /**
     * Process queries from the query channel and trigger full link discovery.
     * Each query gets the same treatment as the initial query: SERP + Hybrid + KG + File Search.
     * Uses flow composition (merge) instead of launch for consistency and proper cancellation.
     */
    private fun processQueryDrivenDiscoveryFlow(
        sessionId: QuerySessionId,
        baseSearchQuery: SearchQuery,
        queryChannel: Channel<String>,
        priorityLinkBuffer: PriorityLinkChannel,
        eventChannel: SendChannel<SearchEvent>
    ): Flow<Unit> {
        return queryChannel.receiveAsFlow()
            .flatMapMerge(concurrency = 3) { query ->
                logger.info("[{}] Triggering full discovery for query: '{}'", sessionId.value, query)
                
                // Create a SearchQuery with this query but same URL context
                val currentSearchQuery = SearchQuery(
                    rawQuery = query,
                    url = baseSearchQuery.url,
                    languagePattern = baseSearchQuery.languagePattern,
                    ocrLanguage = baseSearchQuery.ocrLanguage,
                    includeImages = baseSearchQuery.includeImages
                )
                
                // Run all discovery mechanisms in parallel using flow composition
                merge(
                    // 1. SERP Search
                    flow {
                        try {
                            val serpLinks = webpageLinkDiscoveryService.discoverRelevantLinksBySerper(currentSearchQuery)
                            logger.debug("[{}] SERP discovery for '{}': {} links", sessionId.value, query, serpLinks.size)
                            serpLinks.forEach { priorityLinkBuffer.send(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.error("[{}] SERP discovery for query '{}' failed: {}", sessionId.value, query, e.message)
                        }
                        emit(Unit)
                    },
                    
                    // 2. Hybrid Search (from cache) - discover URLs from cached pages
                    flow {
                        try {
                            val hybridResults = webpageCacheService.searchHybrid(
                                query, baseSearchQuery.url, null, 15, sessionId
                            )
                            val links = hybridResults.map { webpage ->
                                WebpageLink(
                                    url = webpage.url,
                                    source = LinkSource.HYBRID_SEARCH,
                                    reason = "Hybrid search result for: $query",
                                    score = DEFAULT_LINK_SCORE
                                )
                            }
                            logger.debug("[{}] Hybrid discovery for '{}': {} links", sessionId.value, query, links.size)
                            links.forEach { priorityLinkBuffer.send(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.error("[{}] Hybrid discovery for query '{}' failed: {}", sessionId.value, query, e.message)
                        }
                        emit(Unit)
                    },
                    
                    // 3. Knowledge Graph - discover URLs from KG entities
                    flow {
                        try {
                            val urlPrefix = normalizeUrlService.normalize(baseSearchQuery.url) ?: baseSearchQuery.url
                            if (kgHybridRetrievalService.hasDataForUrlPrefix(urlPrefix)) {
                                val kgResult = kgHybridRetrievalService.retrieve(
                                    query = query,
                                    baseUrl = urlPrefix,
                                    maxCacheAge = null,
                                    sessionId = sessionId
                                )
                                val links = kgResult.subgraph?.entities?.flatMap { entity ->
                                    entity.sourceUrls.map { url ->
                                        WebpageLink(
                                            url = url,
                                            source = LinkSource.KNOWLEDGE_GRAPH,
                                            reason = "KG entity: ${entity.name}",
                                            score = DEFAULT_LINK_SCORE
                                        )
                                    }
                                } ?: emptyList()
                                logger.debug("[{}] KG discovery for '{}': {} links", sessionId.value, query, links.size)
                                links.forEach { priorityLinkBuffer.send(it) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.error("[{}] KG discovery for query '{}' failed: {}", sessionId.value, query, e.message)
                        }
                        emit(Unit)
                    },
                    
                    // 4. File Search - discover URLs from file search chunks
                    flow {
                        try {
                            val domain = extractDomain(baseSearchQuery.url)
                            val storeInfo = geminiFileSearchService.findStore(domain)
                            if (storeInfo != null) {
                                val searchResult = geminiFileSearchService.queryStore(
                                    storeName = storeInfo.name,
                                    query = query,
                                    maxAgeMs = null
                                )
                                tokenUsageService.recordTokenUsage(
                                    sessionId, "QueryDrivenDiscovery.FileSearch",
                                    searchResult.tokenUsage.modelName, searchResult.tokenUsage.promptTokens,
                                    searchResult.tokenUsage.outputTokens, searchResult.tokenUsage.totalTokens
                                )
                                val links = searchResult.chunks.map { chunk ->
                                    WebpageLink(
                                        url = chunk.sourceUrl,
                                        source = LinkSource.FILE_SEARCH,
                                        reason = "File search chunk for: $query",
                                        score = DEFAULT_LINK_SCORE
                                    )
                                }.distinctBy { it.url }
                                logger.debug("[{}] File search discovery for '{}': {} links", sessionId.value, query, links.size)
                                links.forEach { priorityLinkBuffer.send(it) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.error("[{}] File search discovery for query '{}' failed: {}", sessionId.value, query, e.message)
                        }
                        emit(Unit)
                    }
                )
            }
    }

    /**
     * Process links from priority buffer in priority order.
     * Pulls from the priority buffer (highest score first) and processes URLs.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun processPriorityLinksFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        budget: SearchBudget,
        priorityLinkBuffer: PriorityLinkChannel,
        recursiveChannel: Channel<WebpageLink>,
        maxCacheAge: Long?,
        proxyConfig: ProxyConfiguration,
        eventChannel: SendChannel<SearchEvent>
    ): Flow<UrlContentResult> {
        val inFlight = ConcurrentHashMap.newKeySet<String>()
        
        // Merge priority buffer with recursive discoveries
        return merge(
            priorityLinkBuffer.receiveAsFlow(),
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
                                        if (priorityLinkBuffer.isClosedForSend() && inFlight.isEmpty()) {
                                            recursiveChannel.close()
                                        }
                                    }
                                    else -> throw e
                                }
                            }
                            .onEach { event ->
                                when (event) {
                                    is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                        // Send newly discovered links to recursive channel
                                        event.discoveredLinks.forEach { newLink ->
                                            recursiveChannel.send(newLink)
                                        }
                                    }

                                    is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                        if (event.wasCached) {
                                            urlAccessService.recordUrlAccess(
                                                sessionId,
                                                CachedUrlAccess(event.url, Clock.System.now())
                                            )
                                        }
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
                                        inFlight.remove(event.url)
                                        if (priorityLinkBuffer.isClosedForSend() && inFlight.isEmpty()) {
                                            recursiveChannel.close()
                                        }
                                    }

                                    is IUrlContentProcessingService.UrlProcessingEvent.HtmlPreviewReady -> {
                                        urlAccessService.recordUrlAccess(
                                            sessionId,
                                            UncachedUrlAccess(event.url, Clock.System.now())
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
     * Accumulator for streaming answer generation with feedback loop support.
     * Tracks sources, queries, and synthesis iterations.
     */
    private data class AnswerAccumulator(
        /** Evaluated sources accumulated from parallel markdown evaluation */
        val evaluatedSources: List<EvaluatedSource> = emptyList(),
        /** Set of URLs that have been processed (for deduplication) */
        val processedUrls: Set<String> = emptySet(),
        /** Whether the answer is complete (status=COMPLETE from synthesis agent) */
        val isComplete: Boolean = false,
        /** Full answer from the last answer synthesis attempt */
        val fullAnswer: String? = null,
        /** URLs of sources that were actually cited in the answer */
        val citedSourceUrls: List<String> = emptyList(),
        /** Image IDs from the answer */
        val imageIds: List<String> = emptyList(),
        /** All queries that have been searched (for deduplication) */
        val searchedQueries: List<String> = emptyList(),
        /** Current synthesis iteration number */
        val iterationNumber: Int = 0,
        /** Sources count per iteration for loop report */
        val sourcesPerIteration: List<Int> = emptyList(),
        /** Answer status from last synthesis */
        val status: AnswerStatus = AnswerStatus.NEED_MORE_INFORMATION,
        /** Follow-up queries from latest synthesis that need deduplication and processing */
        val pendingFollowUpQueries: List<String> = emptyList()
    )

    /**
     * Result from processing a preview source.
     * Stateless - each source is processed independently.
     * Uses a 2-agent flow: HtmlSourceEvalAgent -> StreamingAnswerSynthesisAgent.
     */
    private data class PreviewResult(
        val evaluatedSources: List<EvaluatedSource> = emptyList(),
        val status: AnswerStatus = AnswerStatus.NEED_MORE_INFORMATION,
        val fullAnswer: String? = null,
        /** Follow-up queries from synthesis that need deduplication and processing */
        val pendingFollowUpQueries: List<String> = emptyList()
    ) {
        /**
         * Whether a confident answer was found.
         * Preview path only accepts COMPLETE status for early exit.
         */
        val isAnswerFound: Boolean get() = status == AnswerStatus.COMPLETE
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
        val evaluatedSource: EvaluatedSource
    )

    /**
     * Result from answer synthesis, containing all relevant data from the streaming response.
     */
    private data class SynthesisResult(
        val answer: String,
        val citedSourceUrls: List<String>,
        val assessment: AnswerAssessment,
        val status: AnswerStatus,
        val followUpQueries: List<String>,
        val imageIds: List<String>
    ) {
        /** Whether the answer is complete (status=COMPLETE from synthesis agent) */
        val isComplete: Boolean get() = status == AnswerStatus.COMPLETE
        
        /** Returns a brief summary of unsatisfied dimensions for logging */
        fun getUnsatisfiedSummary(): String {
            val unsatisfied = mutableListOf<String>()
            if (!assessment.answerCompleteness.satisfied) unsatisfied.add("completeness")
            if (!assessment.answerDepth.satisfied) unsatisfied.add("depth")
            if (!assessment.queryIntentionFulfillment.satisfied) unsatisfied.add("intention")
            if (!assessment.sourceConfidence.satisfied) unsatisfied.add("confidence")
            return if (unsatisfied.isEmpty()) "all satisfied" else unsatisfied.joinToString(", ")
        }
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

        // Cooperative cancellation check - exits early if preview path already found an answer
        currentCoroutineContext().ensureActive()

        tokenUsageService.recordTokenUsage(
            sessionId, "MarkdownSourceEvalAgent",
            output.tokenUsage.modelName, output.tokenUsage.promptTokens,
            output.tokenUsage.outputTokens, output.tokenUsage.totalTokens
        )

        return output.evaluatedSource?.let { 
            MarkdownEvalResult(it) 
        }
    }

    /**
     * Synthesize an answer from evaluated sources.
     * Handles streaming collection and token usage recording.
     * Supports the feedback loop with previouslySearchedQueries.
     */
    private suspend fun synthesizeAnswer(
        sessionId: QuerySessionId,
        query: String,
        evaluatedSources: List<EvaluatedSource>,
        previouslySearchedQueries: List<String> = emptyList()
    ): SynthesisResult {
        val answerBuilder = StringBuilder()
        lateinit var status: AnswerStatus
        lateinit var assessment: AnswerAssessment
        var followUpQueries = emptyList<String>()
        var citedSourceUrls = emptyList<String>()
        var imageIds = emptyList<String>()

        streamingAnswerSynthesisAgent.generateStream(
            StreamingAnswerSynthesisInput(
                query = query,
                evaluatedSources = evaluatedSources,
                previouslySearchedQueries = previouslySearchedQueries
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
                    assessment = item.assessment
                    citedSourceUrls = item.citedSourceUrls
                    status = item.status
                    followUpQueries = item.followUpQueries
                    imageIds = item.imageIds
                }
            }
        }

        return SynthesisResult(
            answer = answerBuilder.toString(),
            citedSourceUrls = citedSourceUrls,
            status = status,
            followUpQueries = followUpQueries,
            imageIds = imageIds,
            assessment = assessment
        )
    }

    /**
     * Aggregate a newly evaluated markdown source and attempt answer synthesis.
     * Uses status from synthesis agent to determine if the answer is complete:
     * - COMPLETE = answer is sufficient, stop collecting
     * - NEED_MORE_INFORMATION = keep collecting sources and trigger follow-up searches
     * 
     * Follow-up queries are returned in the accumulator for deduplication via flow composition.
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
            sessionId = sessionId, 
            query = searchQuery.query, 
            evaluatedSources = updatedSources, 
            previouslySearchedQueries = state.searchedQueries
        )

        val newIterationNumber = state.iterationNumber + 1
        val updatedSourcesPerIteration = state.sourcesPerIteration + updatedSources.size

        logger.debug(
            "[{}] Main path synthesis iteration {}: {} sources, status={}, citedSources={}, followUpQueries={}",
            sessionId.value, newIterationNumber, updatedSources.size, synthesis.status,
            synthesis.citedSourceUrls.size, synthesis.followUpQueries.size
        )

        // Emit synthesis iteration event
        eventChannel.send(
            SearchEvent.SynthesisIteration(
                sessionId = sessionId,
                iterationNumber = newIterationNumber,
                status = synthesis.status.name,
                sourceCount = updatedSources.size,
                followUpQueries = synthesis.followUpQueries
            )
        )

        // Only emit SourcesEvaluated if preview path hasn't already produced a confident answer
        if (!isPreviewConfident()) {
            eventChannel.send(
                SearchEvent.SourcesEvaluated(
                    sessionId = sessionId,
                    processedUrlCount = updatedProcessedUrls.size,
                    relevantCount = updatedSources.size,
                    isGoodEnough = synthesis.isComplete,
                    reason = if (synthesis.isComplete) "Answer is complete" 
                             else "Collecting more sources (unsatisfied: ${synthesis.getUnsatisfiedSummary()})"
                )
            )
        }

        // Capture follow-up queries in accumulator for deduplication via flow composition
        // The dedup happens after runningFold using flatMapConcat (concurrency=1)
        val pendingFollowUpQueries = if (synthesis.status == AnswerStatus.NEED_MORE_INFORMATION) {
            synthesis.followUpQueries
        } else {
            emptyList()
        }
        
        if (pendingFollowUpQueries.isNotEmpty()) {
            logger.debug(
                "[{}] Synthesis iteration {} produced {} follow-up queries for dedup",
                sessionId.value, newIterationNumber, pendingFollowUpQueries.size
            )
        }
        
        // Note: searchedQueries will be updated by the dedup flow after deduplication
        val updatedSearchedQueries = state.searchedQueries

        return AnswerAccumulator(
            evaluatedSources = updatedSources,
            processedUrls = updatedProcessedUrls,
            isComplete = synthesis.isComplete,
            fullAnswer = synthesis.answer,
            citedSourceUrls = synthesis.citedSourceUrls,
            imageIds = synthesis.imageIds,
            searchedQueries = updatedSearchedQueries,
            iterationNumber = newIterationNumber,
            sourcesPerIteration = updatedSourcesPerIteration,
            status = synthesis.status,
            pendingFollowUpQueries = pendingFollowUpQueries
        )
    }

    private suspend fun finishQuerySession(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        accumulator: AnswerAccumulator,
        budget: SearchBudget,
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

        eventChannel.send(SearchEvent.AnswerChunk(sessionId, fullAnswer))
        finishSessionWithAnswer(sessionId, accumulator, budget, eventChannel, fullAnswer, answerFound, imageIds, finalLoopReport)
    }

    private suspend fun finishSessionWithAnswer(
        sessionId: QuerySessionId,
        accumulator: AnswerAccumulator,
        budget: SearchBudget,
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
                imageIds = imageIds,
                loopReport = loopReport
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
        // Step 1: Source Eval - extracts facts, filters table facts (service handles caching + token tracking)
        val evalOutput = htmlSourceEvalService.evaluate(HtmlSourceEvalInput(searchQuery, htmlSource), sessionId)
        
        // Cooperative cancellation check - exits early if another source already found an answer
        currentCoroutineContext().ensureActive()

        val evaluatedSource = evalOutput.evaluatedSource ?: run {
            logger.debug("[{}] HTML source not relevant: {}", sessionId.value, htmlSource.url)
            return PreviewResult()
        }

        logger.debug(
            "[{}] HTML source evaluation: url={}, {} facts",
            sessionId.value, htmlSource.url, evaluatedSource.relevantFacts.size
        )

        // Check again before expensive synthesis call
        currentCoroutineContext().ensureActive()

        // Step 2: Answer Synthesis (no previous queries for preview path)
        val synthesis = synthesizeAnswer(
            sessionId = sessionId, 
            query = searchQuery.query, 
            evaluatedSources = listOf(evaluatedSource),
            previouslySearchedQueries = emptyList()
        )

        logger.debug(
            "[{}] Preview synthesis: status={}, {} chars, assessment={}",
            sessionId.value, synthesis.status, synthesis.answer.length, synthesis.getUnsatisfiedSummary()
        )

        // Preview path only accepts COMPLETE status
        val isConfident = synthesis.status == AnswerStatus.COMPLETE
        
        // Capture follow-up queries even from preview path - they'll be processed after merge
        val pendingFollowUpQueries = if (!isConfident) synthesis.followUpQueries else emptyList()

        return PreviewResult(
            evaluatedSources = listOf(evaluatedSource),
            status = synthesis.status,
            fullAnswer = if (isConfident) synthesis.answer else null,
            pendingFollowUpQueries = pendingFollowUpQueries
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
