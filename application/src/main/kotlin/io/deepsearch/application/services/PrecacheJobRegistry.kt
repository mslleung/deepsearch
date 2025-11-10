package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.entities.PrecacheJob
import io.deepsearch.domain.models.entities.PrecacheJobState
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.repositories.IPrecacheJobRepository
import io.deepsearch.domain.exceptions.UrlProcessingException
import io.deepsearch.domain.services.INormalizeUrlService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

interface IPrecacheJobRegistry {
    fun events(jobId: Long): SharedFlow<IPrecacheService.PrecacheEvent>
    suspend fun ensureRunning(job: PrecacheJob)
    suspend fun stop(jobId: Long)
}

class PrecacheJobRegistry(
    private val browserRuntimePool: IBrowserRuntimePool,
    private val normalizeUrlService: INormalizeUrlService,
    private val webpageCacheService: IWebpageCacheService,
    private val urlContentProcessingService: IUrlContentProcessingService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val precacheJobRepository: IPrecacheJobRepository,
    private val dispatchers: IDispatcherProvider,
    private val applicationScope: IApplicationCoroutineScope
) : IPrecacheJobRegistry {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private data class Run(
        val flow: MutableSharedFlow<IPrecacheService.PrecacheEvent>,
        val job: Job
    )

    private val runs = ConcurrentHashMap<Long, Run>()

    init {
        // Resume all in-progress jobs at startup
        applicationScope.scope.launch {
            val active = precacheJobRepository.listAll(PrecacheJobState.IN_PROGRESS)
            active.forEach { job ->
                ensureRunning(job)
            }
        }
    }

    override fun events(jobId: Long): SharedFlow<IPrecacheService.PrecacheEvent> = runs[jobId]?.flow
        ?: MutableSharedFlow(replay = 1)

    override suspend fun ensureRunning(job: PrecacheJob) {
        val id = requireNotNull(job.id) { "Job must have an id" }
        runs.compute(id) { _, existing ->
            if (existing == null) {
                val flow = MutableSharedFlow<IPrecacheService.PrecacheEvent>(replay = 1)
                val coroutine = applicationScope.scope.launch { runPrecache(job, flow) }
                Run(flow, coroutine)
            } else existing
        }
    }

    override suspend fun stop(jobId: Long) {
        runs.remove(jobId)?.job?.cancel()
        val record = precacheJobRepository.findById(jobId) ?: return
        record.markStopped()
        precacheJobRepository.update(record)
    }

    private fun normalize(url: String): String = normalizeUrlService.normalize(url) ?: url

    private suspend fun runPrecache(job: PrecacheJob, flow: MutableSharedFlow<IPrecacheService.PrecacheEvent>) {
        val jobId: Long = requireNotNull(job.id) { "Job must have an id" }
        flow.emit(
            IPrecacheService.PrecacheEvent(
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
            precacheJobRepository.update(job)
            flow.emit(
                IPrecacheService.PrecacheEvent(
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
            logger.warn("Precache failed for {}: {}", job.baseUrl, t.message)
            job.markStopped()
            precacheJobRepository.update(job)
            flow.emit(
                IPrecacheService.PrecacheEvent(
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
     * Reactive flow orchestration for precaching links.
     * Similar to AgenticBrowserSearchOrchestrator but without query filtering.
     */
    private suspend fun extractAndCacheLinks(
        jobId: Long,
        job: PrecacheJob,
        eventFlow: MutableSharedFlow<IPrecacheService.PrecacheEvent>
    ) {
        val visitedUrls = ConcurrentHashMap.newKeySet<String>()
        val processedCount = AtomicInteger(job.processedCount)
        val discoveredLinksFlow = MutableSharedFlow<String>(
            extraBufferCapacity = Int.MAX_VALUE
        )

        // Initial link flow: base URL
        val initialLinkFlow = flowOf(normalize(job.baseUrl))

        // Seed link flow: Google search results
        val seedLinkFlow = createSeedLinkFlow(job.baseUrl)

        // Sitemap link flow
        val sitemapLinkFlow = createSitemapLinkFlow(job)

        // Merge all link sources and process
        merge(initialLinkFlow, seedLinkFlow, sitemapLinkFlow, discoveredLinksFlow)
            .filterByBaseUrl(job.baseUrl)
            .processLinks(
                jobId = jobId,
                job = job,
                visitedUrls = visitedUrls,
                processedCount = processedCount,
                discoveredLinksFlow = discoveredLinksFlow
            )
            .takeWhile { processedCount.get() < job.maxUrlCount }
            .onEach { result ->
                // Emit progress event
                eventFlow.emit(
                    IPrecacheService.PrecacheEvent(
                        jobId = jobId,
                        baseUrl = job.baseUrl,
                        url = result.url,
                        processedCount = processedCount.get(),
                        maxUrlCount = job.maxUrlCount,
                        cachedHit = result.cachedHit,
                        totalQueued = 0,
                        state = job.state
                    )
                )
            }
            .onCompletion {
                logger.info("[{}] Precache complete: {} pages processed", jobId, processedCount.get())
            }
            .collect { /* Terminal operator */ }
    }

    /**
     * Flow extension: filter URLs to only include those under the base URL.
     */
    private fun Flow<String>.filterByBaseUrl(baseUrl: String): Flow<String> = flow {
        collect { url ->
            if (url.startsWith(baseUrl)) {
                emit(url)
            }
        }
    }

    /**
     * Flow extension: process links to extract content and discover new links.
     * Similar to AgenticBrowserSearchOrchestrator.processLinksToMarkdown but for precaching.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Flow<String>.processLinks(
        jobId: Long,
        job: PrecacheJob,
        visitedUrls: MutableSet<String>,
        processedCount: AtomicInteger,
        discoveredLinksFlow: MutableSharedFlow<String>
    ): Flow<PrecacheStepResult> = flatMapMerge(concurrency = 10) { url ->
        flow {
            try {
                val normalizedUrl = normalize(url)

                // Skip if already visited
                if (!visitedUrls.add(normalizedUrl)) {
                    logger.debug("[{}] Skipping already visited URL: {}", jobId, normalizedUrl)
                    return@flow
                }

                // Check budget
                if (processedCount.get() >= job.maxUrlCount) {
                    logger.debug("[{}] Budget exceeded, skipping: {}", jobId, normalizedUrl)
                    return@flow
                }

                // Process URL and collect events
                // Use .catch{} to handle UrlProcessingException
                urlContentProcessingService.processUrlAsFlow(normalizedUrl, cacheExpiryMs = null)
                    .catch { e ->
                        // Handle URL processing errors using Flow's catch operator
                        if (e is CancellationException) {
                            throw e // Always propagate cancellation
                        }
                        
                        if (e is UrlProcessingException) {
                            // URL processing failed - log and continue with next URL
                            logger.warn(
                                "[{}] Failed to process {}: {} (type: {})",
                                jobId,
                                normalizedUrl,
                                e.message,
                                e::class.simpleName
                            )
                            // Still increment processed count for failed URLs
                            processedCount.incrementAndGet()
                            job.incrementProcessed()
                            precacheJobRepository.update(job)
                        } else {
                            // Unexpected exception - log and rethrow
                            logger.error("[{}] Unexpected error processing {}: {}", jobId, normalizedUrl, e.message, e)
                            throw e
                        }
                    }
                    .collect { event ->
                        when (event) {
                            is IUrlContentProcessingService.UrlProcessingEvent.LinkDiscoveryComplete -> {
                                // Emit discovered links to the feedback flow
                                logger.debug(
                                    "[{}] Links discovered for {}: {} links",
                                    jobId,
                                    normalizedUrl,
                                    event.discoveredLinks.size
                                )
                                event.discoveredLinks.forEach { link ->
                                    val normalizedLink = normalize(link.url)
                                    if (normalizedLink.startsWith(job.baseUrl)) {
                                        discoveredLinksFlow.emit(normalizedLink)
                                    }
                                }
                            }
                            is IUrlContentProcessingService.UrlProcessingEvent.MarkdownExtractionComplete -> {
                                // Markdown extraction complete, increment counter and emit result
                                val count = processedCount.incrementAndGet()
                                job.incrementProcessed()
                                precacheJobRepository.update(job)
                                
                                logger.debug(
                                    "[{}] Markdown cached for {}: {} chars (progress: {}/{})",
                                    jobId,
                                    normalizedUrl,
                                    event.markdown.length,
                                    count,
                                    job.maxUrlCount
                                )
                                
                                // Determine if this was a cache hit by checking if markdown was already cached
                                val cachedHit = event.markdown.isNotEmpty()
                                emit(PrecacheStepResult(url = normalizedUrl, cachedHit = cachedHit))
                            }
                        }
                    }
            } catch (e: Exception) {
                logger.warn("[{}] Failed to process {}: {}", jobId, url, e.message)
            }
        }
    }

    /**
     * Create a flow of seed links from Google search.
     */
    private fun createSeedLinkFlow(baseUrl: String): Flow<String> = flow {
        try {
            val seeds = discoverSeeds(baseUrl)
            logger.debug("Discovered {} seed links for base URL: {}", seeds.size, baseUrl)
            seeds.forEach { link ->
                emit(normalize(link.url))
            }
        } catch (e: Exception) {
            logger.error("Failed to discover seeds for {}: {}", baseUrl, e.message, e)
        }
    }

    /**
     * Create a flow of links from sitemap.
     */
    private fun createSitemapLinkFlow(job: PrecacheJob): Flow<String> = flow {
        val sitemapUrl = job.sitemapUrl ?: return@flow
        try {
            val sitemapLinks = webpageLinkDiscoveryService.discoverSitemapLinks(sitemapUrl)
            logger.debug("Sitemap discovered {} links for job {}", sitemapLinks.size, job.id)
            sitemapLinks.forEach { link ->
                emit(normalize(link.url))
            }
        } catch (e: Exception) {
            logger.error("Failed sitemap discovery for job {}: {}", job.id, e.message, e)
        }
    }

    private suspend fun discoverSeeds(baseUrl: String): List<WebpageLink> {
        val query = SearchQuery(query = baseUrl, url = baseUrl)
        return webpageLinkDiscoveryService
            .discoverRelevantLinksByGoogleSearch(query)
            .filter { normalize(it.url).startsWith(baseUrl) }
            .distinctBy { normalize(it.url) }
            .take(50)
    }

    private data class PrecacheStepResult(
        val url: String,
        val cachedHit: Boolean
    )
}


