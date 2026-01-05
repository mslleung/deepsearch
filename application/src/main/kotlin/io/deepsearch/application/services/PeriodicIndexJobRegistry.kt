package io.deepsearch.application.services

import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.exceptions.UrlProcessingException
import io.deepsearch.domain.models.entities.PeriodicIndexJob
import io.deepsearch.domain.models.entities.PeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.*
import io.deepsearch.domain.models.valueobjects.LanguagePattern
import io.deepsearch.domain.ratelimit.IAdaptiveRateLimiter
import io.deepsearch.domain.repositories.IPeriodicIndexJobRepository
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.deepsearch.domain.services.INormalizeUrlService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface IPeriodicIndexJobRegistry {
    fun events(jobId: Long): SharedFlow<IPeriodicIndexJobService.PeriodicIndexEvent>
    suspend fun ensureRunning(job: PeriodicIndexJob)
    suspend fun stop(jobId: Long)
}

@OptIn(ExperimentalTime::class)
class PeriodicIndexJobRegistry(
    private val normalizeUrlService: INormalizeUrlService,
    private val webpageCacheService: IWebpageCacheService,
    private val urlContentProcessingService: IUrlContentProcessingService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val periodicIndexJobRepository: IPeriodicIndexJobRepository,
    private val urlAccessService: IUrlAccessService,
    private val dispatchers: IDispatcherProvider,
    private val applicationScope: IApplicationCoroutineScope,
    private val adaptiveRateLimiter: IAdaptiveRateLimiter,
    private val proxySettingsService: IProxySettingsService
) : IPeriodicIndexJobRegistry {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        /** Default maximum concurrent URL processing across all flows */
        private const val DEFAULT_MAX_CONCURRENCY = 30
        
        /**
         * Calculate adaptive concurrency based on maxUrlCount.
         * Limits concurrency to avoid wasting resources when processing few URLs.
         */
        private fun calculateConcurrency(maxUrlCount: Int): Int = 
            minOf(DEFAULT_MAX_CONCURRENCY, maxOf(1, maxUrlCount))
    }

    private data class Run(
        val flow: MutableSharedFlow<IPeriodicIndexJobService.PeriodicIndexEvent>,
        val job: Job
    )

    private val runs = ConcurrentHashMap<Long, Run>()

    init {
        // Resume all in-progress jobs at startup
        applicationScope.scope.launch {
            val active = periodicIndexJobRepository.listAll(PeriodicIndexJobState.IN_PROGRESS)
            active.forEach { job ->
                ensureRunning(job)
            }
        }
    }

    override fun events(jobId: Long): SharedFlow<IPeriodicIndexJobService.PeriodicIndexEvent> = runs[jobId]?.flow
        ?: MutableSharedFlow(replay = 1)

    override suspend fun ensureRunning(job: PeriodicIndexJob) {
        val id = requireNotNull(job.id) { "Job must have an id" }
        runs.compute(id) { _, existing ->
            if (existing == null) {
                val flow = MutableSharedFlow<IPeriodicIndexJobService.PeriodicIndexEvent>(replay = 1)
                val coroutine = applicationScope.scope.launch(dispatchers.io) { runPeriodicIndex(job, flow) }
                Run(flow, coroutine)
            } else existing
        }
    }

    override suspend fun stop(jobId: Long) {
        runs.remove(jobId)?.job?.cancel()
        val record = periodicIndexJobRepository.findById(jobId) ?: return
        record.markStopped()
        periodicIndexJobRepository.update(record)
    }

    private fun normalize(url: String): String = normalizeUrlService.normalize(url) ?: url
    
    /**
     * Check if a URL matches the language filter for a job.
     * Returns true if the URL should be processed, false if it should be skipped.
     */
    private fun matchesLanguageFilter(url: String, job: PeriodicIndexJob): Boolean {
        val pattern = job.languagePattern?.let { LanguagePattern.parse(it) } ?: return true
        return pattern.matches(url, job.baseUrl)
    }

    private suspend fun runPeriodicIndex(job: PeriodicIndexJob, flow: MutableSharedFlow<IPeriodicIndexJobService.PeriodicIndexEvent>) {
        val jobId: Long = requireNotNull(job.id) { "Job must have an id" }
        
        // Resolve user's proxy configuration for this job's base URL
        // Custom/Premium proxies are used directly; None triggers adaptive bypass strategy
        val proxyConfig = proxySettingsService.resolveProxyForUrl(job.userId, job.baseUrl)
        
        flow.emit(
            IPeriodicIndexJobService.PeriodicIndexEvent(
                jobId = jobId,
                baseUrl = job.baseUrl,
                url = null,
                processedCount = job.processedCount,
                maxUrlCount = job.maxUrlCount,
                cachedHit = null,
                totalQueued = 0,
                state = job.state,
                message = "Starting"
            )
        )

        try {
            extractAndCacheLinks(
                jobId = jobId,
                job = job,
                proxyConfig = proxyConfig,
                eventFlow = flow
            )

            job.markCompleted()
            periodicIndexJobRepository.update(job)
            flow.emit(
                IPeriodicIndexJobService.PeriodicIndexEvent(
                    jobId = jobId,
                    baseUrl = job.baseUrl,
                    url = null,
                    processedCount = job.processedCount,
                    maxUrlCount = job.maxUrlCount,
                    cachedHit = null,
                    totalQueued = 0,
                    state = job.state,
                    message = "Done"
                )
            )
        } catch (t: Throwable) {
            logger.warn("Periodic index failed for {}: {}", job.baseUrl, t.message)
            job.markStopped()
            periodicIndexJobRepository.update(job)
            flow.emit(
                IPeriodicIndexJobService.PeriodicIndexEvent(
                    jobId = jobId,
                    baseUrl = job.baseUrl,
                    url = null,
                    processedCount = job.processedCount,
                    maxUrlCount = job.maxUrlCount,
                    cachedHit = null,
                    totalQueued = 0,
                    state = job.state,
                    message = t.message
                )
            )
        } finally {
            runs.remove(jobId)
        }
    }

    /**
     * Tracks URL processing state for SSE events.
     */
    private class UrlTracker {
        private val processedUrls = java.util.concurrent.ConcurrentLinkedDeque<IPeriodicIndexJobService.ProcessedUrlInfo>()
        private val processingUrls = ConcurrentHashMap.newKeySet<String>()
        private val failedUrls = java.util.concurrent.ConcurrentLinkedDeque<IPeriodicIndexJobService.FailedUrlInfo>()

        fun startProcessing(url: String) {
            processingUrls.add(url)
        }

        fun finishProcessing(url: String, title: String?, cachedHit: Boolean) {
            processingUrls.remove(url)
            processedUrls.addFirst(IPeriodicIndexJobService.ProcessedUrlInfo(
                url = url,
                title = title,
                cachedHit = cachedHit,
                processedAtMs = System.currentTimeMillis()
            ))
            // Keep only last 50 processed URLs to avoid memory bloat
            while (processedUrls.size > 50) {
                processedUrls.removeLast()
            }
        }

        fun failProcessing(url: String, errorMessage: String) {
            processingUrls.remove(url)
            failedUrls.addFirst(IPeriodicIndexJobService.FailedUrlInfo(
                url = url,
                errorMessage = errorMessage,
                failedAtMs = System.currentTimeMillis()
            ))
            // Keep only last 50 failed URLs
            while (failedUrls.size > 50) {
                failedUrls.removeLast()
            }
        }

        fun getProcessedUrls(): List<IPeriodicIndexJobService.ProcessedUrlInfo> = processedUrls.toList()
        fun getProcessingUrls(): List<String> = processingUrls.toList()
        fun getFailedUrls(): List<IPeriodicIndexJobService.FailedUrlInfo> = failedUrls.toList()
    }

    /**
     * Reactive flow orchestration for periodic indexing links.
     * 
     * Architecture:
     * - Discovery flows emit URLs (initial, serper, sitemap)
     * - Recursive channel for links discovered during processing
     * - All sources merged into single flow
     * - Single flatMapMerge(concurrency=adaptive) for processing
     */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private suspend fun extractAndCacheLinks(
        jobId: Long,
        job: PeriodicIndexJob,
        proxyConfig: ProxyConfiguration,
        eventFlow: MutableSharedFlow<IPeriodicIndexJobService.PeriodicIndexEvent>
    ) {
        val seenUrls = ConcurrentHashMap.newKeySet<String>()
        val urlTracker = UrlTracker()
        
        // Adaptive concurrency based on maxUrlCount
        val concurrency = calculateConcurrency(job.maxUrlCount)
        val remainingToProcess = job.maxUrlCount - job.processedCount
        logger.info("[{}] Using adaptive concurrency of {} (maxUrlCount={}, remainingToProcess={})", jobId, concurrency, job.maxUrlCount, remainingToProcess)

        // Fetch carried-over URLs from the last completed job
        val carriedOverUrls = fetchCarriedOverUrls(job)
        logger.info("[{}] Fetched {} carried-over URLs from previous job", jobId, carriedOverUrls.size)

        // Priority buffer for links - maintains global ordering by path depth
        val priorityLinkBuffer = PriorityLinkBuffer()
        
        // Track in-flight processing and discovery completion for termination
        val inFlightProcessing = AtomicInteger(0)
        val discoveryComplete = AtomicBoolean(false)
        
        val tryCloseBuffer = {
            if (discoveryComplete.get() && inFlightProcessing.get() == 0) {
                priorityLinkBuffer.close()
            }
        }

        // Merge all discovery sources - they feed into the priority buffer
        merge(
            // Discovery source 1: Initial URLs + carried-over URLs
            createInitialUrlsFlow(jobId, job, carriedOverUrls),
            // Discovery source 2: Serper search
            createSerperUrlsFlow(jobId, job),
            // Discovery source 3: Sitemap
            createSitemapUrlsFlow(jobId, job)
        )
            .onEach { link -> priorityLinkBuffer.send(link) }
            .onCompletion { 
                discoveryComplete.set(true)
                tryCloseBuffer()
                logger.debug("[{}] All discovery sources complete", jobId)
            }
            .launchIn(CoroutineScope(currentCoroutineContext()))

        // Process links from priority buffer (always in path-depth order)
        // Using .take(remainingToProcess) guarantees exactly the right number of items 
        // flow through - no race conditions since the slot is reserved atomically 
        // before items enter the concurrent flatMapMerge
        priorityLinkBuffer.receiveAsFlow()
            .filter { link ->
                val normalizedUrl = normalize(link.url)
                normalizedUrl.startsWith(job.baseUrl) && 
                    matchesLanguageFilter(normalizedUrl, job) &&
                    seenUrls.add(normalizedUrl)
            }
            .take(remainingToProcess)
            .flatMapMerge(concurrency = concurrency) { link ->
                flow {
                    val normalizedUrl = normalize(link.url)
                    inFlightProcessing.incrementAndGet()
                    
                    try {
                        val result = processUrl(
                            jobId = jobId,
                            job = job,
                            normalizedUrl = normalizedUrl,
                            urlTracker = urlTracker,
                            proxyConfig = proxyConfig,
                            eventFlow = eventFlow,
                            seenUrls = seenUrls,
                            priorityLinkBuffer = priorityLinkBuffer
                        )
                        if (result != null) {
                            emit(result)
                        }
                    } finally {
                        inFlightProcessing.decrementAndGet()
                        tryCloseBuffer()
                    }
                }
            }
            .flowOn(dispatchers.io)
            .onEach { result ->
                job.incrementProcessed()
                periodicIndexJobRepository.update(job)

                eventFlow.emit(
                    IPeriodicIndexJobService.PeriodicIndexEvent(
                        jobId = jobId,
                        baseUrl = job.baseUrl,
                        url = result.url,
                        processedCount = job.processedCount,
                        maxUrlCount = job.maxUrlCount,
                        cachedHit = result.cachedHit,
                        totalQueued = 0,
                        state = job.state,
                        processedUrls = urlTracker.getProcessedUrls(),
                        processingUrls = urlTracker.getProcessingUrls(),
                        failedUrls = urlTracker.getFailedUrls()
                    )
                )
            }
            .flowOn(dispatchers.io.limitedParallelism(1))
            .catch { e ->
                logger.error("[{}] Error during periodic index: {}", jobId, e.message, e)
            }
            .onCompletion {
                logger.info("[{}] Periodic index complete: {} pages processed", jobId, job.processedCount)
            }
            .collect()
    }

    /**
     * Fetch URLs from the last completed job for the same user and baseUrl.
     * Returns only successfully processed URLs (CACHED + UNCACHED).
     */
    private suspend fun fetchCarriedOverUrls(job: PeriodicIndexJob): Set<String> {
        val lastCompletedJob = periodicIndexJobRepository.findLastCompletedByUserIdAndBaseUrl(
            userId = job.userId,
            baseUrl = job.baseUrl
        ) ?: return emptySet()

        val lastJobId = lastCompletedJob.id ?: return emptySet()
        val lastSessionId = PeriodicIndexSessionId(lastJobId)

        // Get successful URLs from the previous job (both cached and uncached)
        val cachedUrls = urlAccessService.getCachedUrls(lastSessionId).map { it.url }
        val uncachedUrls = urlAccessService.getUncachedUrls(lastSessionId).map { it.url }

        return (cachedUrls + uncachedUrls).toSet()
    }

    // ========== Discovery Flows (lightweight, just emit URLs) ==========

    /**
     * Create a flow of initial URLs (base URL + carried-over URLs).
     */
    private fun createInitialUrlsFlow(
        jobId: Long,
        job: PeriodicIndexJob,
        carriedOverUrls: Set<String>
    ): Flow<WebpageLink> = flow {
        // Base URL first, then carried-over URLs
        val allUrls = listOf(job.baseUrl) + carriedOverUrls.filter { it != job.baseUrl }
        
        allUrls.forEach { url ->
            val normalizedUrl = normalize(url)
            emit(WebpageLink(
                url = normalizedUrl, 
                source = LinkSource.ALL_LINKS, 
                reason = "Initial/carried-over URL"
            ))
            logger.debug("[{}] Emitted initial URL: {}", jobId, normalizedUrl)
        }
    }.flowOn(dispatchers.io)

    /**
     * Create a flow of URLs discovered via Serper search.
     */
    private fun createSerperUrlsFlow(
        jobId: Long,
        job: PeriodicIndexJob
    ): Flow<WebpageLink> = flow {
        try {
            val query = SearchQuery(rawQuery = job.baseUrl, url = job.baseUrl)
            val serperLinks = webpageLinkDiscoveryService.discoverRelevantLinksBySerper(query)
                .filter { 
                    val url = normalize(it.url)
                    url.startsWith(job.baseUrl) && matchesLanguageFilter(url, job)
                }
                .distinctBy { normalize(it.url) }
            
            logger.debug("[{}] Serper search discovered {} links for base URL: {}", jobId, serperLinks.size, job.baseUrl)
            
            serperLinks.forEach { link ->
                emit(link.copy(url = normalize(link.url)))
            }
        } catch (e: Exception) {
            logger.error("[{}] Failed Serper search for {}: {}", jobId, job.baseUrl, e.message, e)
        }
    }.flowOn(dispatchers.io)

    /**
     * Create a flow of URLs discovered via sitemap.
     */
    private fun createSitemapUrlsFlow(
        jobId: Long,
        job: PeriodicIndexJob
    ): Flow<WebpageLink> = flow {
        val sitemapUrl = job.sitemapUrl ?: return@flow
        
        try {
            val sitemapLinks = webpageLinkDiscoveryService.discoverSitemapLinks(sitemapUrl)
                .filter { 
                    val url = normalize(it.url)
                    url.startsWith(job.baseUrl) && matchesLanguageFilter(url, job)
                }
            
            logger.debug("[{}] Sitemap discovered {} links", jobId, sitemapLinks.size)
            
            sitemapLinks.forEach { link ->
                emit(link.copy(url = normalize(link.url)))
            }
        } catch (e: Exception) {
            logger.error("[{}] Failed sitemap discovery: {}", jobId, e.message, e)
        }
    }.flowOn(dispatchers.io)

    // ========== URL Processing ==========

    /**
     * Process a single URL: fetch content, extract markdown, discover links.
     * Discovered links are sent to the priority buffer for recursive processing.
     * Returns the result if successful, null if failed.
     */
    private suspend fun processUrl(
        jobId: Long,
        job: PeriodicIndexJob,
        normalizedUrl: String,
        urlTracker: UrlTracker,
        proxyConfig: ProxyConfiguration,
        eventFlow: MutableSharedFlow<IPeriodicIndexJobService.PeriodicIndexEvent>,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        priorityLinkBuffer: PriorityLinkBuffer
    ): PeriodicIndexStepResult? {
        urlTracker.startProcessing(normalizedUrl)
        eventFlow.emit(
            IPeriodicIndexJobService.PeriodicIndexEvent(
                jobId = jobId,
                baseUrl = job.baseUrl,
                url = normalizedUrl,
                processedCount = job.processedCount,
                maxUrlCount = job.maxUrlCount,
                cachedHit = null,
                totalQueued = 0,
                state = job.state,
                message = null,
                processedUrls = urlTracker.getProcessedUrls(),
                processingUrls = urlTracker.getProcessingUrls(),
                failedUrls = urlTracker.getFailedUrls()
            )
        )
        
        val sessionId = PeriodicIndexSessionId(jobId)
        var result: PeriodicIndexStepResult? = null
        
        try {
            adaptiveRateLimiter.withRateLimit(normalizedUrl) {
                urlContentProcessingService.processUrlAsFlow(normalizedUrl, sessionId, job.ocrLanguage, proxyConfig)
                    .collect { event ->
                        when (event) {
                            is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                logger.debug("[{}] URL {} discovered {} links", jobId, normalizedUrl, event.discoveredLinks.size)
                                
                                // Send discovered links to priority buffer for processing
                                event.discoveredLinks
                                    .filter { link -> 
                                        val url = normalize(link.url)
                                        url.startsWith(job.baseUrl) && 
                                            matchesLanguageFilter(url, job) &&
                                            !seenUrls.contains(url)
                                    }
                                    .forEach { link ->
                                        if (!priorityLinkBuffer.isClosedForSend()) {
                                            priorityLinkBuffer.send(link)
                                        }
                                    }
                            }
                            is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                logger.debug("[{}] URL markdown extracted: {} chars", jobId, event.markdown.length)
                                val urlAccess = if (event.wasCached) {
                                    CachedUrlAccess(url = normalizedUrl, timestamp = Clock.System.now())
                                } else {
                                    UncachedUrlAccess(url = normalizedUrl, timestamp = Clock.System.now())
                                }
                                urlAccessService.recordUrlAccess(sessionId, urlAccess)
                                urlTracker.finishProcessing(normalizedUrl, event.title, event.wasCached)
                                result = PeriodicIndexStepResult(
                                    url = normalizedUrl, 
                                    title = event.title, 
                                    cachedHit = event.wasCached
                                )
                            }
                            is IUrlContentProcessingService.UrlProcessingEvent.HtmlPreviewReady -> {
                                // Ignored for periodic index
                            }
                        }
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UrlProcessingException) {
            logger.warn("[{}] Failed to process URL {}: {}", jobId, normalizedUrl, e.message)
            val failedAccess = FailedUrlAccess(
                url = normalizedUrl,
                timestamp = Clock.System.now(),
                exceptionType = e::class.simpleName!!,
                message = e.message ?: "Unknown error"
            )
            urlAccessService.recordUrlAccess(sessionId, failedAccess)
            urlTracker.failProcessing(normalizedUrl, e.message ?: "Unknown error")
        } catch (e: Exception) {
            logger.error("[{}] Unexpected error processing URL {}: {}", jobId, normalizedUrl, e.message, e)
            urlTracker.failProcessing(normalizedUrl, e.message ?: "Unknown error")
        }
        
        return result
    }

    private data class PeriodicIndexStepResult(
        val url: String,
        val title: String?,
        val cachedHit: Boolean
    )
}
