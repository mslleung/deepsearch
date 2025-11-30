package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.entities.PeriodicIndexJob
import io.deepsearch.domain.models.entities.PeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.UncachedUrlAccess
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.repositories.IPeriodicIndexJobRepository
import io.deepsearch.domain.exceptions.UrlProcessingException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import io.deepsearch.domain.services.INormalizeUrlService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

interface IPeriodicIndexJobRegistry {
    fun events(jobId: Long): SharedFlow<IPeriodicIndexJobService.PeriodicIndexEvent>
    suspend fun ensureRunning(job: PeriodicIndexJob)
    suspend fun stop(jobId: Long)
}

@OptIn(ExperimentalTime::class)
class PeriodicIndexJobRegistry(
    private val browserRuntimePool: IBrowserRuntimePool,
    private val normalizeUrlService: INormalizeUrlService,
    private val webpageCacheService: IWebpageCacheService,
    private val urlContentProcessingService: IUrlContentProcessingService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val periodicIndexJobRepository: IPeriodicIndexJobRepository,
    private val urlAccessService: IUrlAccessService,
    private val dispatchers: IDispatcherProvider,
    private val applicationScope: IApplicationCoroutineScope
) : IPeriodicIndexJobRegistry {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

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
                val coroutine = applicationScope.scope.launch { runPeriodicIndex(job, flow) }
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

    private suspend fun runPeriodicIndex(job: PeriodicIndexJob, flow: MutableSharedFlow<IPeriodicIndexJobService.PeriodicIndexEvent>) {
        val jobId: Long = requireNotNull(job.id) { "Job must have an id" }
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
     * Uses channel-based architecture similar to AgenticBrowserSearchOrchestrator.
     * Link sources: initial URL, Serper search, sitemap, and recursive discovered links.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun extractAndCacheLinks(
        jobId: Long,
        job: PeriodicIndexJob,
        eventFlow: MutableSharedFlow<IPeriodicIndexJobService.PeriodicIndexEvent>
    ) {
        val seenUrls = ConcurrentHashMap.newKeySet<String>()
        val processedCount = AtomicInteger(job.processedCount)
        val urlTracker = UrlTracker()

        // Channels for discovered links from different sources
        val initialDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
        val serperDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
        val sitemapDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)
        val recursiveDiscoveredLinksChannel = Channel<WebpageLink>(Channel.UNLIMITED)

        // Merge all link processing flows
        merge(
            processInitialLinkFlow(
                jobId = jobId,
                job = job,
                seenUrls = seenUrls,
                initialDiscoveredLinksChannel = initialDiscoveredLinksChannel,
                urlTracker = urlTracker
            ),
            processSerperSearchLinksFlow(
                jobId = jobId,
                job = job,
                seenUrls = seenUrls,
                serperDiscoveredLinksChannel = serperDiscoveredLinksChannel,
                urlTracker = urlTracker
            ),
            processSitemapLinksFlow(
                jobId = jobId,
                job = job,
                seenUrls = seenUrls,
                sitemapDiscoveredLinksChannel = sitemapDiscoveredLinksChannel,
                urlTracker = urlTracker
            ),
            processRecursiveDiscoveredLinksFlow(
                jobId = jobId,
                job = job,
                seenUrls = seenUrls,
                initialDiscoveredLinksChannel = initialDiscoveredLinksChannel,
                serperDiscoveredLinksChannel = serperDiscoveredLinksChannel,
                sitemapDiscoveredLinksChannel = sitemapDiscoveredLinksChannel,
                recursiveDiscoveredLinksChannel = recursiveDiscoveredLinksChannel,
                urlTracker = urlTracker
            )
        )
            .takeWhile { processedCount.get() < job.maxUrlCount }
            .onEach { result ->
                val count = processedCount.incrementAndGet()
                job.incrementProcessed()
                periodicIndexJobRepository.update(job)

                // Emit progress event with URL tracking
                eventFlow.emit(
                    IPeriodicIndexJobService.PeriodicIndexEvent(
                        jobId = jobId,
                        baseUrl = job.baseUrl,
                        url = result.url,
                        processedCount = count,
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
            .catch { e ->
                logger.error("[{}] Error during periodic index: {}", jobId, e.message, e)
            }
            .onCompletion {
                logger.info("[{}] Periodic index complete: {} pages processed", jobId, processedCount.get())
            }
            .collect { /* Terminal operator */ }
    }

    /**
     * Process the initial base URL.
     * Emits discovered links to the initialDiscoveredLinksChannel.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processInitialLinkFlow(
        jobId: Long,
        job: PeriodicIndexJob,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        initialDiscoveredLinksChannel: Channel<WebpageLink>,
        urlTracker: UrlTracker
    ): Flow<PeriodicIndexStepResult> {
        return flowOf(job.baseUrl)
            .flatMapMerge { url ->
                flow {
                    val normalizedUrl = normalize(url)

                    if (!seenUrls.add(normalizedUrl)) {
                        logger.debug("[{}] Initial URL already seen: {}", jobId, normalizedUrl)
                        initialDiscoveredLinksChannel.close()
                        return@flow
                    }

                    urlTracker.startProcessing(normalizedUrl)
                    val sessionId = PeriodicIndexSessionId(jobId)
                    urlContentProcessingService.processUrlAsFlow(normalizedUrl, sessionId)
                        .catch { e ->
                            if (e is CancellationException) throw e
                            if (e is UrlProcessingException) {
                                logger.warn("[{}] Failed to process initial URL {}: {}", jobId, normalizedUrl, e.message)
                                val failedAccess = FailedUrlAccess(
                                    url = normalizedUrl,
                                    timestamp = Clock.System.now(),
                                    exceptionType = e::class.simpleName!!,
                                    message = e.message ?: "Unknown error"
                                )
                                urlAccessService.recordUrlAccess(sessionId, failedAccess)
                                urlTracker.failProcessing(normalizedUrl, e.message ?: "Unknown error")
                            } else {
                                logger.error("[{}] Unexpected error processing initial URL {}: {}", jobId, normalizedUrl, e.message, e)
                                urlTracker.failProcessing(normalizedUrl, e.message ?: "Unknown error")
                                throw e
                            }
                        }
                        .onEach { event ->
                            when (event) {
                                is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                    logger.debug("[{}] Initial URL discovered {} links", jobId, event.discoveredLinks.size)
                                    event.discoveredLinks
                                        .filter { link -> normalize(link.url).startsWith(job.baseUrl) }
                                        .forEach { link -> initialDiscoveredLinksChannel.send(link) }
                                    initialDiscoveredLinksChannel.close()
                                }
                                is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                    logger.debug("[{}] Initial URL markdown extracted: {} chars", jobId, event.markdown.length)
                                    val urlAccess = if (event.wasCached) {
                                        CachedUrlAccess(url = normalizedUrl, timestamp = Clock.System.now())
                                    } else {
                                        UncachedUrlAccess(url = normalizedUrl, timestamp = Clock.System.now())
                                    }
                                    urlAccessService.recordUrlAccess(sessionId, urlAccess)
                                    urlTracker.finishProcessing(normalizedUrl, event.title, event.wasCached)
                                    emit(PeriodicIndexStepResult(url = normalizedUrl, title = event.title, cachedHit = event.wasCached))
                                }
                            }
                        }
                        .collect {}
                }
            }
            .onCompletion {
                initialDiscoveredLinksChannel.close()
                logger.info("[{}] Initial link processing complete", jobId)
            }
    }

    /**
     * Process links discovered via Serper search.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processSerperSearchLinksFlow(
        jobId: Long,
        job: PeriodicIndexJob,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        serperDiscoveredLinksChannel: Channel<WebpageLink>,
        urlTracker: UrlTracker
    ): Flow<PeriodicIndexStepResult> {
        return createSerperSearchLinkFlow(jobId, job)
            .filter { link ->
                val normalizedUrl = normalize(link.url)
                // Use atomic add() which returns true only if element was NOT already present
                val isNew = seenUrls.add(normalizedUrl)
                if (!isNew) {
                    logger.debug("[{}] Serper link already seen: {}", jobId, normalizedUrl)
                }
                isNew
            }
            .flatMapMerge(concurrency = 10) { link ->
                flow {
                    val normalizedUrl = normalize(link.url)
                    urlTracker.startProcessing(normalizedUrl)
                    val sessionId = PeriodicIndexSessionId(jobId)
                    urlContentProcessingService.processUrlAsFlow(normalizedUrl, sessionId)
                        .catch { e ->
                            if (e is CancellationException) throw e
                            if (e is UrlProcessingException) {
                                logger.warn("[{}] Failed to process Serper link {}: {}", jobId, normalizedUrl, e.message)
                                val failedAccess = FailedUrlAccess(
                                    url = normalizedUrl,
                                    timestamp = Clock.System.now(),
                                    exceptionType = e::class.simpleName!!,
                                    message = e.message ?: "Unknown error"
                                )
                                urlAccessService.recordUrlAccess(sessionId, failedAccess)
                                urlTracker.failProcessing(normalizedUrl, e.message ?: "Unknown error")
                            } else {
                                logger.error("[{}] Unexpected error processing Serper link {}: {}", jobId, normalizedUrl, e.message, e)
                                urlTracker.failProcessing(normalizedUrl, e.message ?: "Unknown error")
                                throw e
                            }
                        }
                        .onEach { event ->
                            when (event) {
                                is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                    logger.debug("[{}] Serper link {} discovered {} links", jobId, normalizedUrl, event.discoveredLinks.size)
                                    event.discoveredLinks
                                        .filter { discovered -> normalize(discovered.url).startsWith(job.baseUrl) }
                                        .forEach { discovered -> serperDiscoveredLinksChannel.send(discovered) }
                                }
                                is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                    logger.debug("[{}] Serper link markdown extracted: {} chars", jobId, event.markdown.length)
                                    val urlAccess = if (event.wasCached) {
                                        CachedUrlAccess(url = normalizedUrl, timestamp = Clock.System.now())
                                    } else {
                                        UncachedUrlAccess(url = normalizedUrl, timestamp = Clock.System.now())
                                    }
                                    urlAccessService.recordUrlAccess(sessionId, urlAccess)
                                    urlTracker.finishProcessing(normalizedUrl, event.title, event.wasCached)
                                    emit(PeriodicIndexStepResult(url = normalizedUrl, title = event.title, cachedHit = event.wasCached))
                                }
                            }
                        }
                        .collect {}
                }
            }
            .onCompletion {
                serperDiscoveredLinksChannel.close()
                logger.info("[{}] Serper search link processing complete", jobId)
            }
    }

    /**
     * Process links discovered via sitemap.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun processSitemapLinksFlow(
        jobId: Long,
        job: PeriodicIndexJob,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        sitemapDiscoveredLinksChannel: Channel<WebpageLink>,
        urlTracker: UrlTracker
    ): Flow<PeriodicIndexStepResult> {
        return createSitemapLinkFlow(jobId, job)
            .filter { link ->
                val normalizedUrl = normalize(link.url)
                // Use atomic add() which returns true only if element was NOT already present
                val isNew = seenUrls.add(normalizedUrl)
                if (!isNew) {
                    logger.debug("[{}] Sitemap link already seen: {}", jobId, normalizedUrl)
                }
                isNew
            }
            .flatMapMerge(concurrency = 10) { link ->
                flow {
                    val normalizedUrl = normalize(link.url)
                    urlTracker.startProcessing(normalizedUrl)
                    val sessionId = PeriodicIndexSessionId(jobId)
                    urlContentProcessingService.processUrlAsFlow(normalizedUrl, sessionId)
                        .catch { e ->
                            if (e is CancellationException) throw e
                            if (e is UrlProcessingException) {
                                logger.warn("[{}] Failed to process sitemap link {}: {}", jobId, normalizedUrl, e.message)
                                val failedAccess = FailedUrlAccess(
                                    url = normalizedUrl,
                                    timestamp = Clock.System.now(),
                                    exceptionType = e::class.simpleName!!,
                                    message = e.message ?: "Unknown error"
                                )
                                urlAccessService.recordUrlAccess(sessionId, failedAccess)
                                urlTracker.failProcessing(normalizedUrl, e.message ?: "Unknown error")
                            } else {
                                logger.error("[{}] Unexpected error processing sitemap link {}: {}", jobId, normalizedUrl, e.message, e)
                                urlTracker.failProcessing(normalizedUrl, e.message ?: "Unknown error")
                                throw e
                            }
                        }
                        .onEach { event ->
                            when (event) {
                                is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                    logger.debug("[{}] Sitemap link {} discovered {} links", jobId, normalizedUrl, event.discoveredLinks.size)
                                    event.discoveredLinks
                                        .filter { discovered -> normalize(discovered.url).startsWith(job.baseUrl) }
                                        .forEach { discovered -> sitemapDiscoveredLinksChannel.send(discovered) }
                                }
                                is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                    logger.debug("[{}] Sitemap link markdown extracted: {} chars", jobId, event.markdown.length)
                                    val urlAccess = if (event.wasCached) {
                                        CachedUrlAccess(url = normalizedUrl, timestamp = Clock.System.now())
                                    } else {
                                        UncachedUrlAccess(url = normalizedUrl, timestamp = Clock.System.now())
                                    }
                                    urlAccessService.recordUrlAccess(sessionId, urlAccess)
                                    urlTracker.finishProcessing(normalizedUrl, event.title, event.wasCached)
                                    emit(PeriodicIndexStepResult(url = normalizedUrl, title = event.title, cachedHit = event.wasCached))
                                }
                            }
                        }
                        .collect {}
                }
            }
            .onCompletion {
                sitemapDiscoveredLinksChannel.close()
                logger.info("[{}] Sitemap link processing complete", jobId)
            }
    }

    /**
     * Process recursively discovered links from all sources.
     * Uses in-flight tracking to know when to close the recursive channel.
     */
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private fun processRecursiveDiscoveredLinksFlow(
        jobId: Long,
        job: PeriodicIndexJob,
        seenUrls: ConcurrentHashMap.KeySetView<String, Boolean>,
        initialDiscoveredLinksChannel: Channel<WebpageLink>,
        serperDiscoveredLinksChannel: Channel<WebpageLink>,
        sitemapDiscoveredLinksChannel: Channel<WebpageLink>,
        recursiveDiscoveredLinksChannel: Channel<WebpageLink>,
        urlTracker: UrlTracker
    ): Flow<PeriodicIndexStepResult> {
        val inFlightLinkDiscoveryProcessing = ConcurrentHashMap.newKeySet<String>()

        return merge(
            merge(
                initialDiscoveredLinksChannel.receiveAsFlow(),
                serperDiscoveredLinksChannel.receiveAsFlow(),
                sitemapDiscoveredLinksChannel.receiveAsFlow()
            )
                .onCompletion {
                    // Close recursive channel if no in-flight processing
                    if (inFlightLinkDiscoveryProcessing.isEmpty()) {
                        recursiveDiscoveredLinksChannel.close()
                    }
                },
            recursiveDiscoveredLinksChannel.receiveAsFlow()
        )
            .filter { link ->
                val normalizedUrl = normalize(link.url)
                if (!normalizedUrl.startsWith(job.baseUrl)) {
                    false
                } else {
                    // Use atomic add() which returns true only if element was NOT already present
                    val isNew = seenUrls.add(normalizedUrl)
                    if (!isNew) {
                        logger.debug("[{}] Recursive link already seen: {}", jobId, normalizedUrl)
                    }
                    isNew
                }
            }
            .flatMapMerge(concurrency = 10) { link ->
                flow {
                    val normalizedUrl = normalize(link.url)
                    val sessionId = PeriodicIndexSessionId(jobId)
                    inFlightLinkDiscoveryProcessing.add(normalizedUrl)
                    urlTracker.startProcessing(normalizedUrl)

                    urlContentProcessingService.processUrlAsFlow(normalizedUrl, sessionId)
                        .catch { e ->
                            when (e) {
                                is CancellationException -> throw e
                                is UrlProcessingException -> {
                                    logger.warn("[{}] Failed to process recursive link {}: {}", jobId, normalizedUrl, e.message)
                                    val failedAccess = FailedUrlAccess(
                                        url = normalizedUrl,
                                        timestamp = Clock.System.now(),
                                        exceptionType = e::class.simpleName!!,
                                        message = e.message ?: "Unknown error"
                                    )
                                    urlAccessService.recordUrlAccess(sessionId, failedAccess)
                                    urlTracker.failProcessing(normalizedUrl, e.message ?: "Unknown error")
                                    inFlightLinkDiscoveryProcessing.remove(normalizedUrl)
                                    checkAndCloseRecursiveChannel(
                                        initialDiscoveredLinksChannel,
                                        serperDiscoveredLinksChannel,
                                        sitemapDiscoveredLinksChannel,
                                        recursiveDiscoveredLinksChannel,
                                        inFlightLinkDiscoveryProcessing
                                    )
                                }
                                else -> {
                                    logger.error("[{}] Unexpected error processing recursive link {}: {}", jobId, normalizedUrl, e.message, e)
                                    urlTracker.failProcessing(normalizedUrl, e.message ?: "Unknown error")
                                    throw e
                                }
                            }
                        }
                        .onEach { event ->
                            when (event) {
                                is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                    logger.debug("[{}] Recursive link {} discovered {} links", jobId, normalizedUrl, event.discoveredLinks.size)
                                    inFlightLinkDiscoveryProcessing.remove(normalizedUrl)

                                    val newDiscoveredLinks = event.discoveredLinks.filter { discovered ->
                                        val normalizedDiscovered = normalize(discovered.url)
                                        normalizedDiscovered.startsWith(job.baseUrl) && !seenUrls.contains(normalizedDiscovered)
                                    }
                                    newDiscoveredLinks.forEach { discovered ->
                                        recursiveDiscoveredLinksChannel.send(discovered)
                                    }

                                    logger.debug("[{}] In-flight links count: {}", jobId, inFlightLinkDiscoveryProcessing.size)

                                    if (newDiscoveredLinks.isEmpty()) {
                                        checkAndCloseRecursiveChannel(
                                            initialDiscoveredLinksChannel,
                                            serperDiscoveredLinksChannel,
                                            sitemapDiscoveredLinksChannel,
                                            recursiveDiscoveredLinksChannel,
                                            inFlightLinkDiscoveryProcessing
                                        )
                                    }
                                }
                                is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                    logger.debug("[{}] Recursive link markdown extracted: {} chars", jobId, event.markdown.length)
                                    val urlAccess = if (event.wasCached) {
                                        CachedUrlAccess(url = normalizedUrl, timestamp = Clock.System.now())
                                    } else {
                                        UncachedUrlAccess(url = normalizedUrl, timestamp = Clock.System.now())
                                    }
                                    urlAccessService.recordUrlAccess(sessionId, urlAccess)
                                    urlTracker.finishProcessing(normalizedUrl, event.title, event.wasCached)
                                    emit(PeriodicIndexStepResult(url = normalizedUrl, title = event.title, cachedHit = event.wasCached))
                                }
                            }
                        }
                        .collect {}
                }
            }
            .onCompletion { cause ->
                if (cause != null) {
                    logger.info("[{}] Recursive link processing cancelled: {}", jobId, cause.message)
                } else {
                    logger.info("[{}] Recursive link processing complete", jobId)
                }
            }
    }

    /**
     * Check if all source channels are closed and no in-flight processing,
     * then close the recursive channel.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun checkAndCloseRecursiveChannel(
        initialDiscoveredLinksChannel: Channel<WebpageLink>,
        serperDiscoveredLinksChannel: Channel<WebpageLink>,
        sitemapDiscoveredLinksChannel: Channel<WebpageLink>,
        recursiveDiscoveredLinksChannel: Channel<WebpageLink>,
        inFlightLinkDiscoveryProcessing: MutableSet<String>
    ) {
        if (initialDiscoveredLinksChannel.isClosedForSend &&
            serperDiscoveredLinksChannel.isClosedForSend &&
            sitemapDiscoveredLinksChannel.isClosedForSend &&
            inFlightLinkDiscoveryProcessing.isEmpty()
        ) {
            recursiveDiscoveredLinksChannel.close()
        }
    }

    /**
     * Create a flow of links from Serper search.
     */
    private fun createSerperSearchLinkFlow(jobId: Long, job: PeriodicIndexJob): Flow<WebpageLink> = flow {
        try {
            val query = SearchQuery(query = job.baseUrl, url = job.baseUrl)
            val serperLinks = webpageLinkDiscoveryService.discoverRelevantLinksBySerper(query)
                .filter { normalize(it.url).startsWith(job.baseUrl) }
                .distinctBy { normalize(it.url) }
                .take(50)
            logger.debug("[{}] Serper search discovered {} links for base URL: {}", jobId, serperLinks.size, job.baseUrl)
            serperLinks.forEach { link -> emit(link) }
        } catch (e: Exception) {
            logger.error("[{}] Failed Serper search for {}: {}", jobId, job.baseUrl, e.message, e)
        }
    }

    /**
     * Create a flow of links from sitemap.
     */
    private fun createSitemapLinkFlow(jobId: Long, job: PeriodicIndexJob): Flow<WebpageLink> = flow {
        val sitemapUrl = job.sitemapUrl ?: return@flow
        try {
            val sitemapLinks = webpageLinkDiscoveryService.discoverSitemapLinks(sitemapUrl)
                .filter { normalize(it.url).startsWith(job.baseUrl) }
            logger.debug("[{}] Sitemap discovered {} links", jobId, sitemapLinks.size)
            sitemapLinks.forEach { link -> emit(link) }
        } catch (e: Exception) {
            logger.error("[{}] Failed sitemap discovery: {}", jobId, e.message, e)
        }
    }

    private data class PeriodicIndexStepResult(
        val url: String,
        val title: String?,
        val cachedHit: Boolean
    )
}
