package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IBypassStrategyService
import io.deepsearch.application.services.IProxySettingsService
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.entities.BatchIconData
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.deepsearch.domain.models.entities.BatchImageData
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchUrlProcessingStage
import io.deepsearch.domain.models.entities.BatchUrlSnapshotData
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.LanguagePattern
import io.deepsearch.domain.ratelimit.IAdaptiveRateLimiter
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.INormalizeUrlService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 1: Browser-based crawl + extract handler.
 * 
 * Combines link discovery and browser extraction in a single browser visit per URL.
 * Uses sliding window concurrency for optimal throughput.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CrawlAndExtractHandler(
    private val batchJobRepository: IBatchPeriodicIndexJobRepository,
    private val batchUrlStateRepository: IBatchUrlStateRepository,
    private val browserPool: IBrowserPool,
    private val bypassStrategyService: IBypassStrategyService,
    private val proxySettingsService: IProxySettingsService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val normalizeUrlService: INormalizeUrlService,
    private val adaptiveRateLimiter: IAdaptiveRateLimiter,
    private val eventEmitter: BatchEventEmitter
) : IBatchStageHandler {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    companion object {
        private const val BROWSER_EXTRACTION_CONCURRENCY = 5
    }

    /**
     * Result of processing a single URL.
     */
    private data class CrawlExtractResult(
        val url: String,
        val discoveredLinks: List<String>,
        val success: Boolean
    )

    override suspend fun execute(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 1: Crawl + Extract for {} (concurrency: {})", 
            jobId, job.baseUrl, BROWSER_EXTRACTION_CONCURRENCY)
        eventEmitter.emit(job, eventFlow, "Stage 1: Crawling & extracting webpages...")
        
        // Resolve user's proxy configuration for this job's base URL
        // Custom/Included proxies are used directly; None triggers adaptive bypass strategy
        val proxyConfig = proxySettingsService.resolveProxyForUrl(job.userId, job.baseUrl)

        // Thread-safe collections
        val seenUrls = ConcurrentHashMap.newKeySet<String>()
        val processedCount = AtomicInteger(0)
        
        // Channel for URL work items with in-flight tracking
        val urlChannel = Channel<String>(Channel.UNLIMITED)
        val inFlightCount = AtomicInteger(0)
        
        // Load already processed URLs
        val existingUrls = batchUrlStateRepository.findByJobId(jobId)
        existingUrls.forEach { 
            seenUrls.add(it.url)
            if (it.isExtracted()) {
                processedCount.incrementAndGet()
            }
        }
        
        // Re-queue PENDING URLs that were discovered but not yet extracted (for resumability)
        val pendingUrls = existingUrls.filter { 
            it.stage == BatchUrlProcessingStage.PENDING && !it.isFailed() 
        }
        pendingUrls.forEach { urlState ->
            // URL is already in seenUrls from above, just queue it for processing
            inFlightCount.incrementAndGet()
            urlChannel.send(urlState.url)
        }
        
        // Seed with base URL if not already processed
        val normalizedBaseUrl = normalizeUrlService.normalize(job.baseUrl) ?: job.baseUrl
        if (seenUrls.add(normalizedBaseUrl) && matchesLanguageFilter(normalizedBaseUrl, job)) {
            inFlightCount.incrementAndGet()
            urlChannel.send(normalizedBaseUrl)
        }
        
        // If nothing to process, skip stage
        if (inFlightCount.get() == 0) {
            urlChannel.close()
            job.advanceToNextStage()
            batchJobRepository.update(job)
            eventEmitter.emit(job, eventFlow, "Stage 1 complete: No new URLs to process")
            return
        }
        
        // Sliding window processing with flatMapMerge
        urlChannel.consumeAsFlow()
            .flatMapMerge(concurrency = BROWSER_EXTRACTION_CONCURRENCY) { url ->
                flow {
                    // Skip if we've hit the limit
                    if (processedCount.get() >= job.maxUrlCount) {
                        return@flow
                    }
                    
                    val result = processSingleUrl(jobId, url, proxyConfig)
                    emit(result)
                    
                    // Feed discovered links back into channel (if under limit)
                    if (processedCount.get() < job.maxUrlCount) {
                        result.discoveredLinks
                            .asSequence()
                            .map { normalizeUrlService.normalize(it) ?: it }
                            .filter { link ->
                                link.startsWith(job.baseUrl) &&
                                matchesLanguageFilter(link, job) &&
                                seenUrls.size < job.maxUrlCount
                            }
                            .sortedBy { it.count { c -> c == '/' } } // Prioritize shallower paths
                            .take(job.maxUrlCount - seenUrls.size)
                            .filter { seenUrls.add(it) } // Thread-safe add returns true if new
                            .forEach { link ->
                                inFlightCount.incrementAndGet()
                                urlChannel.send(link)
                            }
                    }
                }.onCompletion {
                    // Close channel when no more work in flight
                    if (inFlightCount.decrementAndGet() == 0) {
                        urlChannel.close()
                    }
                }
            }
            .collect { result ->
                if (result.success) {
                    val newCount = processedCount.incrementAndGet()
                    job.urlsProcessed = newCount
                    
                    // Emit progress periodically (every 10 URLs or at limit)
                    if (newCount % 10 == 0 || newCount >= job.maxUrlCount) {
                        batchJobRepository.update(job)
                        eventEmitter.emit(job, eventFlow, "Processed $newCount/${job.maxUrlCount} URLs")
                    }
                }
            }

        // Final update
        batchJobRepository.update(job)
        
        logger.info("[{}] Stage 1 complete: Processed {} URLs", jobId, job.urlsProcessed)
        job.advanceToNextStage()
        batchJobRepository.update(job)
        eventEmitter.emit(job, eventFlow, "Stage 1 complete: ${job.urlsProcessed} pages crawled & extracted")
    }

    /**
     * Process a single URL: crawl + extract in one browser visit.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.time.ExperimentalTime::class)
    private suspend fun processSingleUrl(
        jobId: Long,
        url: String,
        proxyConfig: ProxyConfiguration
    ): CrawlExtractResult {
        var urlState = batchUrlStateRepository.findByJobIdAndUrl(jobId, url)
        
        if (urlState == null) {
            urlState = BatchUrlState(jobId = jobId, url = url)
            batchUrlStateRepository.create(urlState)
        }
        
        if (urlState.isExtracted()) {
            return CrawlExtractResult(url, emptyList(), success = false)
        }
        
        return try {
            var discoveredLinks = emptyList<String>()
            
            adaptiveRateLimiter.withRateLimit(url) {
                // Use bypass strategy for adaptive proxy handling:
                // - Custom/Included proxies are used directly
                // - None triggers direct-first, then free rotating proxy fallback
                bypassStrategyService.withPageWithBypass(url, proxyConfig) { page ->
                    // Navigation is already done by bypassStrategyService
                    val html = page.getFullHtml()
                    
                    // Parallel browser captures
                    val (snapshot, icons, images) = coroutineScope {
                        val snapshotDeferred = async { page.capturePageSnapshot() }
                        val iconsDeferred = async { page.extractIcons() }
                        val imagesDeferred = async { page.extractImages() }
                        Triple(snapshotDeferred.await(), iconsDeferred.await(), imagesDeferred.await())
                    }

                    // Convert icons/images to serializable format
                    val batchIcons = icons.map { icon ->
                        val hashBytes = MessageDigest.getInstance("SHA-256").digest(icon.bytes)
                        BatchIconData(
                            bytesBase64 = kotlin.io.encoding.Base64.encode(icon.bytes),
                            mimeType = icon.mimeType.value,
                            cssSelectors = icon.cssSelectors,
                            hashBase64 = kotlin.io.encoding.Base64.encode(hashBytes)
                        )
                    }
                    
                    val batchImages = images.map { image ->
                        val hashBytes = MessageDigest.getInstance("SHA-256").digest(image.bytes)
                        BatchImageData(
                            bytesBase64 = kotlin.io.encoding.Base64.encode(image.bytes),
                            mimeType = image.mimeType.value,
                            cssSelectors = image.cssSelectors,
                            hashBase64 = kotlin.io.encoding.Base64.encode(hashBytes)
                        )
                    }

                    // Store HTML, bounding boxes, icons, and images for later stages
                    val snapshotData = BatchUrlSnapshotData(
                        html = html,
                        boundingBoxes = snapshot.boundingBoxes,
                        icons = batchIcons.takeIf { it.isNotEmpty() },
                        images = batchImages.takeIf { it.isNotEmpty() }
                    )
                    urlState.markExtracted(
                        json.encodeToString(snapshotData),
                        snapshot.title,
                        snapshot.description
                    )
                    batchUrlStateRepository.update(urlState)
                    
                    discoveredLinks = webpageLinkDiscoveryService.discoverAllLinks(html, url)
                        .map { it.url }
                }
            }
            
            CrawlExtractResult(url, discoveredLinks, success = true)
        } catch (e: Exception) {
            logger.warn("[{}] Failed to crawl+extract {}: {}", jobId, url, e.message)
            urlState.markFailed(e.message ?: "Crawl+extract failed")
            batchUrlStateRepository.update(urlState)
            CrawlExtractResult(url, emptyList(), success = false)
        }
    }

    private fun matchesLanguageFilter(url: String, job: BatchPeriodicIndexJob): Boolean {
        val pattern = job.languagePattern?.let { LanguagePattern.parse(it) } ?: return true
        return pattern.matches(url, job.baseUrl)
    }
}

