package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.application.services.IUrlContentProcessingService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.application.services.SearchEvent
import io.deepsearch.application.services.WebpageCacheService
import io.deepsearch.domain.services.IGeminiFileSearchService
import io.deepsearch.domain.agents.AnswerStreamItem
import io.deepsearch.domain.agents.AnswerSynthesisInput
import io.deepsearch.domain.agents.IAnswerReviewerAgent
import io.deepsearch.domain.agents.IAnswerSynthesisAgent
import io.deepsearch.domain.agents.IQueryBreakdownAgent
import io.deepsearch.domain.agents.ISerpQueryOptimizationAgent
import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.SerpQueryOptimizationInput
import io.deepsearch.domain.agents.IStreamingSourceShortlistAgent
import io.deepsearch.domain.agents.StreamingSourceShortlistInput
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.exceptions.MarkdownConversionException
import io.deepsearch.domain.exceptions.NetworkConnectionException
import io.deepsearch.domain.ext.chunkedWithTimeout
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.MarkdownSource
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
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
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
    private val streamingSourceShortlistAgent: IStreamingSourceShortlistAgent,
    private val answerSynthesisAgent: IAnswerSynthesisAgent,
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService,
    private val webpageCacheService: WebpageCacheService,
    private val geminiFileSearchService: IGeminiFileSearchService,
    private val dispatchers: IDispatcherProvider,
    private val tokenUsageService: io.deepsearch.application.services.ILlmTokenUsageService
) : IAgenticBrowserSearchOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun execute(
        searchQuery: SearchQuery,
        maxCacheAge: Long?,
        apiKeyId: ApiKeyId
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

            // Launch flow processing
            var answerAccumulator = AnswerAccumulator()
            merge(
                processInitialLinkFlow(
                    sessionId,
                    searchQuery,
                    seenUrls,
                    initialDiscoveredLinksChannel,
                    maxCacheAge,
                    channel
                ),
                processSerperSearchLinksFlow(
                    sessionId,
                    searchQuery,
                    seenUrls,
                    serperSearchDiscoveredLinksChannel,
                    maxCacheAge,
                    channel
                ),
                processHybridSearchFlow(
                    sessionId,
                    searchQuery,
                    seenUrls,
                    maxCacheAge,
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
                    maxCacheAge, channel
                )
            )
                .cancellable()
                .filter { it.markdown.isNotBlank() }
                .chunkedWithTimeout(chunkSize = 5, timeoutMs = 1000)
                .runningFold(AnswerAccumulator()) { state, markdownResults ->
                    aggregateMarkdownResultIntoAnswer(sessionId, searchQuery, state, markdownResults, channel)
                }
                .onEach { answerAccumulator = it }
                .filter { answerAccumulator -> answerAccumulator.isComplete }
                .take(1)
                .onEach { completedAnswerAccumulator -> // should only be one emission
                    val completionEvent = finishQuerySession(
                        sessionId,
                        searchQuery,
                        completedAnswerAccumulator,
                        budget,
                        channel
                    )
                    send(completionEvent)
                }
                .onCompletion { // answer did not complete, but the upstream flow completed, return our incomplete answer
                    if (answerAccumulator.isComplete) {
                        // already called the early exit in the onEach above
                        return@onCompletion
                    }
                    val completionEvent = finishQuerySession(sessionId, searchQuery, answerAccumulator, budget, channel)
                    send(completionEvent)
                }
                .single()
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
        eventChannel: SendChannel<SearchEvent>
    ): Flow<MarkdownSource> {
        return flowOf(searchQuery.url)
            .flatMapMerge { url ->
                val normalizedUrl = normalizeUrlService.normalize(url) ?: url
                eventChannel.send(SearchEvent.UrlProcessingStarted(sessionId, normalizedUrl))

                urlContentProcessingService.processUrlAsFlow(normalizedUrl, searchQuery.query, maxCacheAge, sessionId)
                    .filter { event ->
                        val eventUrl = normalizeUrlService.normalize(event.url) ?: event.url
                        // Use atomic add() which returns true only if element was NOT already present
                        seenUrls.add(eventUrl)
                    }
                    .onEach { event ->
                        when (event) {
                            is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                event.discoveredLinks.forEach { initialDiscoveredLinksChannel.send(it) }
                                initialDiscoveredLinksChannel.close()
                            }

                            is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                val urlAccess = if (event.wasCached) CachedUrlAccess(event.url, Clock.System.now())
                                else UncachedUrlAccess(event.url, Clock.System.now())
                                urlAccessService.recordUrlAccess(sessionId, urlAccess)

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
                        }
                    }
                    .filterIsInstance<IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete>()
                    .map { MarkdownSource(it.url, it.title, it.description, it.markdown) }
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
        eventChannel: SendChannel<SearchEvent>
    ): Flow<MarkdownSource> {
        return processDiscoveredLinksFlow(
            sessionId, searchQuery, seenUrls,
            createSerperSearchLinkDiscoveryFlow(sessionId, searchQuery),
            discoveredLinksChannel, maxCacheAge, eventChannel
        )
    }

    /**
     * Common processing logic for discovered links.
     */
    private fun processDiscoveredLinksFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        linkSource: Flow<WebpageLink>,
        discoveredLinksChannel: Channel<WebpageLink>,
        maxCacheAge: Long?,
        eventChannel: SendChannel<SearchEvent>
    ): Flow<MarkdownSource> {
        return linkSource
            .filter { link ->
                val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url
                // Use atomic add() which returns true only if element was NOT already present
                seenUrls.add(normalizedUrl)
            }
            .flatMapMerge(concurrency = 100) { link ->
                flow {
                    val normalizedUrl = normalizeUrlService.normalize(link.url) ?: link.url
                    eventChannel.send(SearchEvent.UrlProcessingStarted(sessionId, normalizedUrl))
                    urlContentProcessingService.processUrlAsFlow(
                        normalizedUrl,
                        searchQuery.query,
                        maxCacheAge,
                        sessionId
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
                                    val urlAccess = if (event.wasCached) CachedUrlAccess(event.url, Clock.System.now())
                                    else UncachedUrlAccess(event.url, Clock.System.now())
                                    urlAccessService.recordUrlAccess(sessionId, urlAccess)
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
                            }
                        }
                        .filterIsInstance<IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete>()
                        .map { MarkdownSource(it.url, it.title, it.description, it.markdown) }
                        .collect { emit(it) }
                }
            }
            .onCompletion { discoveredLinksChannel.close() }
    }

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
        eventChannel: SendChannel<SearchEvent>
    ): Flow<MarkdownSource> {
        val inFlight = ConcurrentHashMap.newKeySet<String>()

        return merge(
            merge(initialChannel.receiveAsFlow(), serperChannel.receiveAsFlow(), hybridChannel.receiveAsFlow(), fileSearchChannel.receiveAsFlow())
                .onCompletion { if (inFlight.isEmpty()) recursiveChannel.close() },
            recursiveChannel.receiveAsFlow()
        )
            .takeWhile { !querySessionService.isBudgetExceeded(sessionId, budget) }
            .filter { link ->
                val url = normalizeUrlService.normalize(link.url) ?: link.url
                // Use atomic add() which returns true only if element was NOT already present
                seenUrls.add(url)
            }
            .flatMapMerge(concurrency = 100) { link ->
                flow {
                    val url = normalizeUrlService.normalize(link.url) ?: link.url
                    inFlight.add(url)
                    eventChannel.send(SearchEvent.UrlProcessingStarted(sessionId, url))
                    urlContentProcessingService.processUrlAsFlow(url, searchQuery.query, maxCacheAge, sessionId)
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
                                    val newLinks = event.discoveredLinks.filter {
                                        !seenUrls.contains(
                                            normalizeUrlService.normalize(it.url) ?: it.url
                                        )
                                    }
                                    newLinks.forEach { recursiveChannel.send(it) }
                                    if (initialChannel.isClosedForSend && serperChannel.isClosedForSend && hybridChannel.isClosedForSend && fileSearchChannel.isClosedForSend && inFlight.isEmpty() && newLinks.isEmpty()) {
                                        recursiveChannel.close()
                                    }
                                }

                                is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                    val access = if (event.wasCached) CachedUrlAccess(
                                        event.url,
                                        Clock.System.now()
                                    ) else UncachedUrlAccess(event.url, Clock.System.now())
                                    urlAccessService.recordUrlAccess(sessionId, access)
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
                            }
                        }
                        .filterIsInstance<IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete>()
                        .map { MarkdownSource(it.url, it.title, it.description, it.markdown) }
                        .collect { emit(it) }
                }
            }
    }

    private fun processHybridSearchFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        maxCacheAge: Long?,
        hybridChannel: Channel<WebpageLink>,
        eventChannel: SendChannel<SearchEvent>
    ): Flow<MarkdownSource> = flow {
        val webpages = webpageCacheService.searchHybrid(searchQuery.query, searchQuery.url, maxCacheAge, 15, sessionId)
            .filter { !it.markdown.isNullOrBlank() && !it.html.isNullOrBlank() }

        seenUrls.addAll(webpages.map { it.url })

        webpages.forEach { webpage ->
            eventChannel.send(SearchEvent.UrlProcessingStarted(sessionId, webpage.url))
            urlAccessService.recordUrlAccess(sessionId, CachedUrlAccess(webpage.url, Clock.System.now()))
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
            emit(MarkdownSource(webpage.url, webpage.title, webpage.description, webpage.markdown!!))
        }

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
    ): Flow<MarkdownSource> = flow {
        try {
            // Extract domain from URL
            val domain = extractDomain(searchQuery.url)
            logger.debug("[{}] Querying file search store for domain: {}", sessionId.value, domain)

            // Try to get existing store for this domain
            val storeInfo = try {
                geminiFileSearchService.getOrCreateStore(domain)
            } catch (e: Exception) {
                logger.debug("[{}] No file search store for domain {}: {}", sessionId.value, domain, e.message)
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
                    val sourceUrl = chunk.sourceUrl.ifBlank { searchQuery.url }
                    seenUrls.add(sourceUrl)

                    eventChannel.send(
                        SearchEvent.UrlProcessed(
                            sessionId = sessionId,
                            url = sourceUrl,
                            accessType = "FILE_SEARCH",
                            title = chunk.fileName,
                            description = "Retrieved from file search",
                            markdownLength = chunk.content.length
                        )
                    )

                    emit(
                        MarkdownSource(
                            url = sourceUrl,
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

    private fun createSerperSearchLinkDiscoveryFlow(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery
    ): Flow<WebpageLink> = flow {
        try {
            val effectiveQuery = optimizeQueryForSerp(sessionId, searchQuery)
            webpageLinkDiscoveryService.discoverRelevantLinksBySerper(effectiveQuery).forEach { emit(it) }
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
        if (wordCount <= 10) {
            logger.debug("[{}] Query has {} words, skipping SERP optimization", sessionId.value, wordCount)
            return searchQuery
        }

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

    private data class AnswerAccumulator(
        val currentShortlist: List<ShortlistedSource> = emptyList(),
        val allMarkdownSources: List<MarkdownSource> = emptyList(),
        val isComplete: Boolean = false
    )

    private suspend fun aggregateMarkdownResultIntoAnswer(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        state: AnswerAccumulator,
        markdownSources: List<MarkdownSource>,
        eventChannel: SendChannel<SearchEvent>
    ): AnswerAccumulator {
        val newSources = state.allMarkdownSources + markdownSources

        val output = streamingSourceShortlistAgent.generate(
            StreamingSourceShortlistInput(searchQuery.query, state.currentShortlist, markdownSources)
        )

        tokenUsageService.recordTokenUsage(
            sessionId, "StreamingSourceShortlistAgent",
            output.tokenUsage.modelName, output.tokenUsage.promptTokens,
            output.tokenUsage.outputTokens, output.tokenUsage.totalTokens
        )

        eventChannel.send(
            SearchEvent.ShortlistUpdated(
                sessionId = sessionId,
                processedUrlCount = newSources.size,
                shortlistedCount = output.updatedShortlist.size,
                isGoodEnough = output.isGoodEnough,
                reason = output.reason
            )
        )

        return AnswerAccumulator(output.updatedShortlist, newSources, output.isGoodEnough)
    }

    private suspend fun finishQuerySession(
        sessionId: QuerySessionId,
        searchQuery: SearchQuery,
        accumulator: AnswerAccumulator,
        budget: SearchBudget,
        eventChannel: SendChannel<SearchEvent>
    ): SearchEvent.SessionCompleted {
        // Stream the answer and emit chunks
        var fullAnswer = ""
        answerSynthesisAgent.generateStream(
            AnswerSynthesisInput(searchQuery.query, accumulator.currentShortlist)
        ).collect { item ->
            when (item) {
                is AnswerStreamItem.Chunk -> {
                    fullAnswer += item.text
                    eventChannel.send(SearchEvent.AnswerChunk(sessionId, item.text))
                }

                is AnswerStreamItem.Complete -> {
                    // Record token usage from the final streaming chunk
                    tokenUsageService.recordTokenUsage(
                        sessionId, "AnswerSynthesisAgent",
                        item.tokenUsage.modelName, item.tokenUsage.promptTokens,
                        item.tokenUsage.outputTokens, item.tokenUsage.totalTokens
                    )
                }
            }
        }

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
            "ANSWER_COMPLETE" -> querySessionService.completeSessionAnswerComplete(sessionId, fullAnswer)
            "BUDGET_EXCEEDED" -> querySessionService.completeSessionBudgetExceeded(sessionId, fullAnswer, budget)
            else -> querySessionService.completeSessionLinksExhausted(sessionId, fullAnswer)
        }

        // Fetch full session detail for the completed event
        val sessionDetail = querySessionService.getSessionDetailInternal(sessionId)

        return SearchEvent.SessionCompleted(
            sessionId = sessionId,
            finishReason = finishReason,
            sessionDetail = sessionDetail
        )
    }
}
