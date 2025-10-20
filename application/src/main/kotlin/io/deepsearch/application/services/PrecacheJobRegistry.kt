package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.DispatcherProvider
import io.deepsearch.domain.models.entities.PrecacheJob
import io.deepsearch.domain.models.entities.PrecacheJobState
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.repositories.IPrecacheJobRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

interface IPrecacheJobRegistry {
    fun events(jobId: Long): SharedFlow<IPrecacheService.PrecacheEvent>
    suspend fun ensureRunning(job: PrecacheJob)
    suspend fun stop(jobId: Long)
}

class PrecacheJobRegistry(
    private val browserPool: IBrowserPool,
    private val normalizeUrlService: INormalizeUrlService,
    private val webpageCacheService: IWebpageCacheService,
    private val urlContentProcessingService: IUrlContentProcessingService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val jobRepository: IPrecacheJobRepository,
    private val dispatchers: DispatcherProvider
) : IPrecacheJobRegistry {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private data class Run(
        val flow: MutableSharedFlow<IPrecacheService.PrecacheEvent>,
        val job: Job
    )

    private val runs = ConcurrentHashMap<Long, Run>()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    init {
        // Resume all in-progress jobs at startup
        scope.launch {
            val active = jobRepository.listAll(PrecacheJobState.IN_PROGRESS)
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
            if (existing == null || existing.job.isCompleted) {
                val flow = MutableSharedFlow<IPrecacheService.PrecacheEvent>(replay = 1)
                val coroutine = scope.launch { runPrecache(job, flow) }
                Run(flow, coroutine)
            } else existing
        }
    }

    override suspend fun stop(jobId: Long) {
        runs.remove(jobId)?.job?.cancel()
        val record = jobRepository.findById(jobId) ?: return
        record.markStopped(System.currentTimeMillis())
        jobRepository.update(record)
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

        val browser = browserPool.acquireBrowser()
        try {
            val seeds = discoverSeeds(job.baseUrl)
            val initial = buildList {
                add(WebpageLink(url = job.baseUrl, source = LinkSource.LINK_RELEVANCE, reason = "base"))
                addAll(seeds)
            }

            val queue: ArrayDeque<String> = ArrayDeque(
                initial
                    .map { normalize(it.url) }
                    .filter { it.startsWith(job.baseUrl) }
                    .distinct()
            )

            val visited: MutableSet<String> = LinkedHashSet()
            var processed = job.processedCount

            while (queue.isNotEmpty() && processed < job.maxUrlCount) {
                val remainingBudget = job.maxUrlCount - processed

                val currentBatch = buildList {
                    while (isNotEmpty(queue) && size < remainingBudget) {
                        val next = queue.removeFirst()
                        val normalized = normalize(next)
                        if (normalized.startsWith(job.baseUrl) && !visited.contains(normalized) && !any { it == normalized }) {
                            add(normalized)
                        }
                    }
                }

                if (currentBatch.isEmpty()) break

                val results = coroutineScope {
                    currentBatch.map { url ->
                        async(dispatchers.io) {
                            when (val cacheResult = webpageCacheService.getCachedMarkdown(url)) {
                                is CachedWebpageResult.Hit -> {
                                    val cachedHtml = cacheResult.webpageMarkdown.html
                                    val discovered = if (cachedHtml != null) {
                                        webpageLinkDiscoveryService.discoverAllLinks(cachedHtml, job.baseUrl)
                                    } else emptyList()
                                    PrecacheStepResult(url = url, cachedHit = true, discoveredLinks = discovered)
                                }
                                is CachedWebpageResult.Miss, is CachedWebpageResult.Expired -> {
                                    val processingResult = urlContentProcessingService.processUrl(url, browser)
                                    PrecacheStepResult(url = url, cachedHit = false, discoveredLinks = processingResult.discoveredLinks)
                                }
                            }
                        }
                    }.awaitAll()
                }

                results.forEach { result ->
                    visited.add(result.url)
                    enqueue(queue, job.baseUrl, visited, result.discoveredLinks)
                    processed += 1
                    job.incrementProcessed(System.currentTimeMillis())
                    jobRepository.update(job)
                    flow.emit(
                        IPrecacheService.PrecacheEvent(
                            jobId = jobId,
                            baseUrl = job.baseUrl,
                            url = result.url,
                            processedCount = processed,
                            maxUrlCount = job.maxUrlCount,
                            cachedHit = result.cachedHit,
                            totalQueued = queue.size,
                            state = job.state
                        )
                    )
                }
            }

            job.markCompleted(System.currentTimeMillis())
            jobRepository.update(job)
            flow.emit(
                IPrecacheService.PrecacheEvent(
                    jobId = jobId,
                    baseUrl = job.baseUrl,
                    url = null,
                    processedCount = processed,
                    maxUrlCount = job.maxUrlCount,
                    cachedHit = null,
                    totalQueued = 0,
                    state = job.state,
                    message = "Done"
                )
            )
        } catch (t: Throwable) {
            logger.warn("Precache failed for {}: {}", job.baseUrl, t.message)
            job.markStopped(System.currentTimeMillis())
            jobRepository.update(job)
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
            try { browser.close() } catch (_: Throwable) {}
            runs.remove(jobId)
        }
    }

    private fun enqueue(
        queue: ArrayDeque<String>,
        baseUrl: String,
        visited: Set<String>,
        links: List<WebpageLink>
    ) {
        links.forEach { link ->
            val n = normalize(link.url)
            if (n.startsWith(baseUrl) && !visited.contains(n)) {
                queue.addLast(n)
            }
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
        val cachedHit: Boolean,
        val discoveredLinks: List<WebpageLink>
    )

    private fun isNotEmpty(queue: ArrayDeque<String>): Boolean = queue.isNotEmpty()
}


