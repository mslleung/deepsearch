package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.DispatcherProvider
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class PrecacheEvent(
    val baseUrl: String,
    val url: String?,
    val processedCount: Int,
    val cachedHit: Boolean?,
    val totalQueued: Int,
    val state: String,
    val message: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

interface IPrecacheService {
    fun startOrAttach(baseUrl: String, maxUrlCount: Int): SharedFlow<PrecacheEvent>
}

class PrecacheService(
    private val browserPool: IBrowserPool,
    private val normalizeUrlService: INormalizeUrlService,
    private val webpageCacheService: IWebpageCacheService,
    private val urlContentProcessingService: IUrlContentProcessingService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val dispatchers: DispatcherProvider
) : IPrecacheService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private data class Run(
        val flow: MutableSharedFlow<PrecacheEvent>,
        val job: Job
    )

    // Single map to keep run state per base URL
    private val runs = ConcurrentHashMap<String, Run>()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    override fun startOrAttach(baseUrl: String, maxUrlCount: Int): SharedFlow<PrecacheEvent> {
        val normalizedBase = normalize(baseUrl)
        val run = runs.compute(normalizedBase) { _, existing ->
            if (existing == null || existing.job.isCompleted) {
                val flow = MutableSharedFlow<PrecacheEvent>(replay = 1)
                val job = scope.launch { runPrecache(normalizedBase, maxUrlCount, flow) }
                Run(flow, job)
            } else existing
        }!!
        return run.flow
    }

    private fun normalize(url: String): String = normalizeUrlService.normalize(url) ?: url

    private suspend fun runPrecache(baseUrl: String, maxUrlCount: Int, flow: MutableSharedFlow<PrecacheEvent>) {
        flow.emit(PrecacheEvent(baseUrl, null, 0, null, 0, state = "Running", message = "Starting"))

        val browser = browserPool.acquireBrowser()
        try {
            // Seed with base URL + Google search links for the domain
            val seeds = discoverSeeds(baseUrl)
            val initial = buildList {
                add(WebpageLink(url = baseUrl, source = LinkSource.LINK_RELEVANCE, reason = "base"))
                addAll(seeds)
            }

            val queue: ArrayDeque<String> = ArrayDeque(
                initial
                    .map { normalize(it.url) }
                    .filter { it.startsWith(baseUrl) }
                    .distinct()
            )

            val visited: MutableSet<String> = LinkedHashSet()
            var processed = 0

            while (queue.isNotEmpty() && processed < maxUrlCount) {
                val remainingBudget = maxUrlCount - processed

                // Prepare current batch up to remaining budget, de-duplicated and not yet visited
                val currentBatch = buildList {
                    while (isNotEmpty(queue) && size < remainingBudget) {
                        val next = queue.removeFirst()
                        val normalized = normalize(next)
                        if (normalized.startsWith(baseUrl) && !visited.contains(normalized) && !any { it == normalized }) {
                            add(normalized)
                        }
                    }
                }

                if (currentBatch.isEmpty()) break

                // Process the batch in parallel
                val results = coroutineScope {
                    currentBatch.map { url ->
                        async(dispatchers.io) {
                            when (val cacheResult = webpageCacheService.getCachedMarkdown(url)) {
                                is CachedWebpageResult.Hit -> {
                                    // Skip navigation; use cached HTML for discovery
                                    val cachedHtml = cacheResult.webpageMarkdown.html
                                    val discovered = if (cachedHtml != null) {
                                        webpageLinkDiscoveryService.discoverRelevantLinksByAgent("", cachedHtml)
                                    } else emptyList()
                                    PrecacheStepResult(url = url, cachedHit = true, discoveredLinks = discovered)
                                }
                                is CachedWebpageResult.Miss, is CachedWebpageResult.Expired -> {
                                    val processingResult = urlContentProcessingService.processUrl(url, "", browser)
                                    PrecacheStepResult(url = url, cachedHit = false, discoveredLinks = processingResult.discoveredLinks)
                                }
                            }
                        }
                    }.awaitAll()
                }

                // Apply results and enqueue newly discovered links
                results.forEach { result ->
                    visited.add(result.url)
                    enqueue(queue, baseUrl, visited, result.discoveredLinks)
                    processed += 1
                    flow.emit(PrecacheEvent(baseUrl, result.url, processed, result.cachedHit, queue.size, "Running"))
                }
            }

            flow.emit(PrecacheEvent(baseUrl, null, processed, null, 0, state = "Completed", message = "Done"))
        } catch (t: Throwable) {
            logger.warn("Precache failed for {}: {}", baseUrl, t.message)
            flow.emit(PrecacheEvent(baseUrl, null, 0, null, 0, state = "Error", message = t.message))
        } finally {
            try { browser.close() } catch (_: Throwable) {}
            runs.remove(baseUrl)
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
        val query = SearchQuery(query = "", url = baseUrl)
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


