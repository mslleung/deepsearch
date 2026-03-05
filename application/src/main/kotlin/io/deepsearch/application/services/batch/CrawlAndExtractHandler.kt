package io.deepsearch.application.services.batch

import io.deepsearch.application.services.ContentTypeResult
import io.deepsearch.application.services.IHttpContentTypeResolutionService
import io.deepsearch.application.services.IProxyResolutionService
import io.deepsearch.application.services.IProxySettingsService
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchUrlProcessingStage
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.LanguagePattern
import io.deepsearch.domain.ratelimit.IAdaptiveRateLimiter
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.INormalizeUrlService
import io.deepsearch.domain.services.ITemporaryFileStorageService
import io.deepsearch.domain.services.IBatchSnapshotStorageService
import io.deepsearch.domain.services.ExtractionData
import io.deepsearch.domain.services.ScreenshotData
import io.deepsearch.domain.services.IconData
import io.deepsearch.domain.services.ImageData
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.domain.exceptions.AllProxiesFailedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64

/**
 * Stage 1: Browser-based crawl + extract handler.
 * 
 * Combines link discovery and browser extraction in a single browser visit per URL.
 * Uses sliding window concurrency for optimal throughput.
 * 
 * Now supports both HTML and FILE URLs:
 * - HTML URLs: Browser extraction + link discovery
 * - FILE URLs: Download + store for background upload to Gemini File Search
 */
@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CrawlAndExtractHandler(
    private val batchJobRepository: IBatchPeriodicIndexJobRepository,
    private val batchUrlStateRepository: IBatchUrlStateRepository,
    private val browserPool: IBrowserPool,
    private val httpContentTypeResolutionService: IHttpContentTypeResolutionService,
    private val proxyResolutionService: IProxyResolutionService,
    private val proxySettingsService: IProxySettingsService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val normalizeUrlService: INormalizeUrlService,
    private val adaptiveRateLimiter: IAdaptiveRateLimiter,
    private val temporaryFileStorage: ITemporaryFileStorageService,
    private val snapshotStorage: IBatchSnapshotStorageService,
    private val eventEmitter: BatchEventEmitter
) : IBatchStageHandler {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

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
            // Count both HTML (EXTRACTED) and FILE (PENDING_FILE_UPLOAD+) as processed in Stage 1
            if (it.isExtracted() || it.stage == BatchUrlProcessingStage.PENDING_FILE_UPLOAD || 
                it.stage == BatchUrlProcessingStage.FILE_UPLOADED || it.stage == BatchUrlProcessingStage.CACHED) {
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
     * Process a single URL: detect content type, then crawl+extract (HTML) or download (FILE).
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
        
        // Skip if already processed (either HTML or FILE track)
        if (urlState.isExtracted() || urlState.stage == BatchUrlProcessingStage.PENDING_FILE_UPLOAD ||
            urlState.stage == BatchUrlProcessingStage.FILE_UPLOADED) {
            return CrawlExtractResult(url, emptyList(), success = false)
        }
        
        return try {
            adaptiveRateLimiter.withRateLimit(url) {
                // First, detect content type
                when (val contentType = httpContentTypeResolutionService.resolve(url)) {
                    is ContentTypeResult.Html -> {
                        // HTML: Use browser extraction
                        processHtmlUrl(jobId, url, urlState, proxyConfig, contentType)
                    }
                    is ContentTypeResult.SupportedFile -> {
                        // FILE: Store for background upload
                        processFileUrl(jobId, url, urlState, contentType)
                    }
                    is ContentTypeResult.Unsupported -> {
                        logger.debug("[{}] Unsupported content type for {}: {}", jobId, url, contentType.contentType)
                        urlState.markFailed("Unsupported content type: ${contentType.contentType}")
                        batchUrlStateRepository.update(urlState)
                        CrawlExtractResult(url, emptyList(), success = false)
                    }
                    is ContentTypeResult.FileTooLarge -> {
                        logger.debug("[{}] File too large for {}: {} bytes", jobId, url, contentType.contentLength)
                        urlState.markFailed("File too large: ${contentType.contentLength} bytes")
                        batchUrlStateRepository.update(urlState)
                        CrawlExtractResult(url, emptyList(), success = false)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("[{}] Failed to process {}: {}", jobId, url, e.message)
            urlState.markFailed(e.message ?: "Processing failed")
            batchUrlStateRepository.update(urlState)
            CrawlExtractResult(url, emptyList(), success = false)
        }
    }
    
    /**
     * Process an HTML URL using browser extraction.
     * Stores all extraction data in GCS instead of database.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun processHtmlUrl(
        jobId: Long,
        url: String,
        urlState: BatchUrlState,
        proxyConfig: ProxyConfiguration,
        htmlResult: ContentTypeResult.Html
    ): CrawlExtractResult {
        var discoveredLinks = emptyList<String>()
        
        // Use browser for full extraction (icons, images, snapshots)
        withResolvedPage(url, proxyConfig) { page ->
            val html = page.getFullHtml()
            
            // Inject stable IDs before any extraction (ensures consistent IDs across all captures)
            page.injectStableIds()
            
            // Parallel browser captures (including screenshot for vision-based identification)
            // Screenshot is captured once and shared between:
            // - Visual identification (semantic + tables in single vision call)
            // - Image extraction (fallback cropping for CORS-blocked images)
            data class BrowserCaptures(
                val snapshot: IBrowserPage.PageSnapshotWithMetadata,
                val icons: List<IBrowserPage.Icon>,
                val images: List<IBrowserPage.WebImage>,
                val screenshot: IBrowserPage.Screenshot,
                val hiddenBboxData: IBrowserPage.HiddenContainerBoundingBoxes
            )
            val captures = coroutineScope {
                val snapshotDeferred = async { page.capturePageSnapshot() }
                val iconsDeferred = async { page.extractIcons() }
                val screenshotDeferred = async { page.takeFullPageScreenshot() }
                // Image extraction uses the shared screenshot for fallback (avoids duplicate screenshot capture)
                val imagesDeferred = async { 
                    val screenshot = screenshotDeferred.await()
                    page.extractImagesWithScreenshot(screenshot)
                }
                // Hidden container bounding boxes - captured LAST as it may trigger re-renders
                val hiddenBboxDeferred = async {
                    snapshotDeferred.await()
                    screenshotDeferred.await()
                    iconsDeferred.await()
                    imagesDeferred.await()
                    page.captureHiddenContainerBoundingBoxes()
                }
                BrowserCaptures(
                    snapshotDeferred.await(),
                    iconsDeferred.await(),
                    imagesDeferred.await(),
                    screenshotDeferred.await(),
                    hiddenBboxDeferred.await()
                )
            }
            val (snapshot, icons, images, screenshot, hiddenBboxData) = captures

            // Convert icons to storage format (binary, not Base64)
            val iconDataList = icons.map { icon ->
                val hashBytes = MessageDigest.getInstance("SHA-256").digest(icon.bytes)
                val urlSafeHash = Base64.UrlSafe.encode(hashBytes).trimEnd('=')
                IconData(
                    hash = urlSafeHash,
                    bytes = icon.bytes,
                    mimeType = icon.mimeType.value,
                    cssSelectors = icon.cssSelectors
                )
            }
            
            // Convert images to storage format (binary, not Base64)
            val imageDataList = images.map { image ->
                val hashBytes = MessageDigest.getInstance("SHA-256").digest(image.bytes)
                val urlSafeHash = Base64.UrlSafe.encode(hashBytes).trimEnd('=')
                ImageData(
                    hash = urlSafeHash,
                    bytes = image.bytes,
                    mimeType = image.mimeType.value,
                    cssSelectors = image.cssSelectors
                )
            }

            // Generate URL hash for directory naming
            val urlHashBytes = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            val urlHash = Base64.UrlSafe.encode(urlHashBytes).trimEnd('=')

            // Store all extraction data in GCS (not database)
            val extractionData = ExtractionData(
                html = html,
                title = snapshot.title,
                description = snapshot.description,
                boundingBoxes = snapshot.boundingBoxes,
                screenshot = ScreenshotData(screenshot.bytes, screenshot.mimeType.value),
                icons = iconDataList,
                images = imageDataList,
                hiddenContainerBoundingBoxes = hiddenBboxData
            )
            
            val basePath = snapshotStorage.storeExtraction(jobId, urlHash, extractionData)
            
            // Store post-stable-ID HTML for lightweight indexing pipeline
            snapshotStorage.storeSnapshotHtml(basePath, snapshot.html)
            
            // Update URL state with GCS path (not JSON blob)
            urlState.markExtracted(basePath, snapshot.title, snapshot.description)
            batchUrlStateRepository.update(urlState)
            
            discoveredLinks = webpageLinkDiscoveryService.discoverAllLinks(html, url)
                .map { it.url }
            
            logger.debug("[{}] HTML extracted to GCS: {} ({} icons, {} images)", 
                jobId, url, iconDataList.size, imageDataList.size)
        }
        
        logger.debug("[{}] HTML extracted: {} ({} links discovered)", jobId, url, discoveredLinks.size)
        return CrawlExtractResult(url, discoveredLinks, success = true)
    }
    
    /**
     * Process a FILE URL by storing it in GCS for background upload to Gemini File Search.
     * 
     * Files are stored in Google Cloud Storage (not database) for:
     * - Server restart survival
     * - Avoiding database bloat
     * - Cost-effective storage (GCS free tier: 5GB)
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun processFileUrl(
        jobId: Long,
        url: String,
        urlState: BatchUrlState,
        fileResult: ContentTypeResult.SupportedFile
    ): CrawlExtractResult {
        // Calculate file hash for deduplication
        val hashBytes = MessageDigest.getInstance("SHA-256").digest(fileResult.bytes)
        val fileHash = kotlin.io.encoding.Base64.encode(hashBytes)
        
        // Extract filename from URL for title
        val fileName = url.substringAfterLast('/').substringBefore('?').ifEmpty { "document" }
        
        // Store file bytes in GCS (not database) for background worker
        val storagePath = temporaryFileStorage.store(
            jobId = jobId,
            fileHash = fileHash,
            bytes = fileResult.bytes,
            mimeType = fileResult.mimeType
        )
        
        urlState.markPendingFileUpload(
            mimeType = fileResult.mimeType,
            hash = fileHash,
            storagePath = storagePath,
            fileName = fileName
        )
        batchUrlStateRepository.update(urlState)
        
        logger.debug("[{}] File stored in GCS for upload: {} ({}, {} bytes) → {}", 
            jobId, url, fileResult.mimeType, fileResult.bytes.size, storagePath)
        
        // Files don't discover new links
        return CrawlExtractResult(url, emptyList(), success = true)
    }

    /**
     * Execute block with resolved proxy configuration.
     * Handles navigation internally and orchestrates fanout if multiple proxies are returned.
     */
    private suspend fun <T> withResolvedPage(
        url: String,
        proxyConfig: ProxyConfiguration,
        block: suspend (IBrowserPage) -> T
    ): T {
        val proxyUrls = proxyResolutionService.resolve(url, proxyConfig)
        return if (proxyUrls.size <= 1) {
            browserPool.withPage(proxyUrls.firstOrNull()) { page ->
                page.navigate(url)
                block(page)
            }
        } else {
            withProxyFanout(proxyUrls, url, block)
        }
    }

    /**
     * Try multiple proxies in parallel. First successful navigation wins and proceeds with extraction.
     * Other attempts are cancelled once a winner is selected.
     */
    private suspend fun <T> withProxyFanout(
        proxyUrls: List<String>,
        navigateUrl: String,
        block: suspend (IBrowserPage) -> T
    ): T = coroutineScope {
        logger.debug("Fanout to {} proxies for {}", proxyUrls.size, navigateUrl)

        val result = CompletableDeferred<T>()
        val winnerSelected = AtomicBoolean(false)
        val failureCount = AtomicInteger(0)

        val jobs = proxyUrls.map { proxyUrl ->
            launch {
                try {
                    browserPool.withPage(proxyUrl) { page ->
                        page.navigate(navigateUrl)

                        // First successful navigation wins - others just exit
                        if (winnerSelected.compareAndSet(false, true)) {
                            val output = block(page)
                            result.complete(output)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("Proxy {} failed: {}", proxyUrl, e.message)
                    if (failureCount.incrementAndGet() == proxyUrls.size) {
                        result.completeExceptionally(
                            AllProxiesFailedException(proxyUrls.first(), proxyUrls.size, e)
                        )
                    }
                }
            }
        }

        try {
            result.await()
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    private fun matchesLanguageFilter(url: String, job: BatchPeriodicIndexJob): Boolean {
        val pattern = job.languagePattern?.let { LanguagePattern.parse(it) } ?: return true
        return pattern.matches(url, job.baseUrl)
    }
}
