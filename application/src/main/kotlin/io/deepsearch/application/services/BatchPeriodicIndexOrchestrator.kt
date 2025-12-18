package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.entities.BatchIconData
import io.deepsearch.domain.models.entities.BatchImageData
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.models.entities.BatchUrlSnapshotData
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.LanguagePattern
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.ratelimit.IAdaptiveRateLimiter
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.BatchEmbeddingRequest
import io.deepsearch.domain.services.BatchJobState
import io.deepsearch.domain.services.CssSelectorReplacement
import io.deepsearch.domain.services.IBoundingBoxDerivationService
import io.deepsearch.domain.services.IGeminiBatchService
import io.deepsearch.domain.services.IJsoupDomService
import io.deepsearch.domain.services.INormalizeUrlService
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay

// JSON response wrappers for batch result parsing
@kotlinx.serialization.Serializable
private data class IconBatchResponseWrapper(
    val icons: List<IconLabelWrapper> = emptyList()
)

@kotlinx.serialization.Serializable
private data class IconLabelWrapper(
    val label: String? = null
)

@kotlinx.serialization.Serializable
private data class ImageClassificationResponseWrapper(
    val imageType: String = "ILLUSTRATIVE",
    val text: String? = null,
    val containsTable: Boolean = false
)

@kotlinx.serialization.Serializable
private data class TableExtractionResponseWrapper(
    val text: String? = null
)

/**
 * Event emitted during batch periodic index processing.
 * Provides stage-based progress tracking for the frontend.
 */
@kotlinx.serialization.Serializable
data class BatchPeriodicIndexEvent(
    val jobId: Long,
    val baseUrl: String,
    val state: BatchPeriodicIndexJobState,
    val stage: Int,
    val stageDescription: String,
    /** Stage 1: URLs that have been crawled + browser extracted */
    val urlsProcessed: Int,
    /** Stage 2: URLs with content LLM processing complete */
    val urlsContentProcessed: Int,
    /** Stage 3: URLs with final LLM processing complete */
    val urlsFinalProcessed: Int,
    /** Stage 4: URLs written to cache */
    val urlsCached: Int,
    val totalUrls: Int,
    val geminiBatchJobId: String? = null,
    val estimatedCompletionTime: String? = null,
    val errorMessage: String? = null,
    val message: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Interface for batch periodic index orchestration.
 */
interface IBatchPeriodicIndexOrchestrator {
    /**
     * Start or resume a batch periodic index job.
     */
    suspend fun startOrResume(job: BatchPeriodicIndexJob)

    /**
     * Stop a running batch job.
     */
    suspend fun stop(jobId: Long)

    /**
     * Get the event stream for a job.
     */
    fun events(jobId: Long): SharedFlow<BatchPeriodicIndexEvent>
}

/**
 * Orchestrates the 4-stage batch periodic index pipeline.
 * 
 * Stage 1: CRAWL_AND_EXTRACT - Combined link discovery + browser extraction (single browser visit per URL)
 * Stage 2: BATCHING_CONTENT_LLM - Submit parallel batch jobs for semantic + table identification
 * Stage 3: BATCHING_FINAL_LLM - Submit batch job for table interpretation
 * Stage 4: WRITING_CACHE - Write markdown/embeddings to cache
 * 
 * Each stage persists progress to the database for resumption after server restarts.
 * 
 * Key optimizations:
 * - Stage 1: Combines crawling and extraction in a single browser visit per URL
 * - Stage 2: Runs semantic and table identification batches IN PARALLEL (not sequentially)
 */
@OptIn(ExperimentalTime::class, kotlinx.coroutines.FlowPreview::class)
class BatchPeriodicIndexOrchestrator(
    private val batchJobRepository: IBatchPeriodicIndexJobRepository,
    private val batchUrlStateRepository: IBatchUrlStateRepository,
    private val geminiBatchService: IGeminiBatchService,
    private val browserPool: IBrowserPool,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val normalizeUrlService: INormalizeUrlService,
    private val webpageCacheService: IWebpageCacheService,
    private val jsoupDomService: IJsoupDomService,
    private val boundingBoxDerivationService: IBoundingBoxDerivationService,
    private val dispatchers: IDispatcherProvider,
    private val applicationScope: IApplicationCoroutineScope,
    private val adaptiveRateLimiter: IAdaptiveRateLimiter,
    // All LLM processing via services (with caching) - mirrors WebpageExtractionService
    private val semanticIdentificationService: ISemanticIdentificationService,
    private val tableIdentificationService: ITableIdentificationService,
    private val tableInterpretationService: ITableInterpretationService,
    private val webpageIconInterpretationService: IWebpageIconInterpretationService,
    private val webpageImageTextExtractionService: IWebpageImageTextExtractionService
) : IBatchPeriodicIndexOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    companion object {
        // Concurrency limit for browser extraction in stage 1
        private const val BROWSER_EXTRACTION_CONCURRENCY = 5
        private const val BATCH_POLL_INTERVAL_MS = 60_000L // 1 minute
        private const val MAX_BATCH_POLL_ATTEMPTS = 1440 // 24 hours at 1 minute intervals
    }

    private data class Run(
        val eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        val coroutineJob: Job
    )

    private val runs = ConcurrentHashMap<Long, Run>()

    init {
        // Resume all in-progress batch jobs at startup
        applicationScope.scope.launch {
            val activeJobs = batchJobRepository.findActiveJobs()
            logger.info("Found {} active batch jobs to resume", activeJobs.size)
            activeJobs.forEach { job ->
                startOrResume(job)
            }
        }
    }

    override suspend fun startOrResume(job: BatchPeriodicIndexJob) {
        val jobId = requireNotNull(job.id) { "Job must have an ID" }
        
        runs.compute(jobId) { _, existing ->
            if (existing == null || !existing.coroutineJob.isActive) {
                val eventFlow = MutableSharedFlow<BatchPeriodicIndexEvent>(replay = 1)
                val coroutineJob = applicationScope.scope.launch(dispatchers.io) {
                    runPipeline(job, eventFlow)
                }
                Run(eventFlow, coroutineJob)
            } else {
                existing
            }
        }
    }

    override suspend fun stop(jobId: Long) {
        runs.remove(jobId)?.coroutineJob?.cancel()
        val job = batchJobRepository.findById(jobId) ?: return
        
        // Cancel any active Gemini batch job
        job.geminiBatchJobId?.let { batchId ->
            try {
                geminiBatchService.cancelBatch(batchId)
            } catch (e: Exception) {
                logger.warn("Failed to cancel Gemini batch job: {}", batchId, e)
            }
        }
        
        job.markStopped()
        batchJobRepository.update(job)
    }

    override fun events(jobId: Long): SharedFlow<BatchPeriodicIndexEvent> {
        return runs[jobId]?.eventFlow ?: MutableSharedFlow(replay = 1)
    }

    private suspend fun runPipeline(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        
        try {
            job.markResumed()
            batchJobRepository.update(job)
            
            emitEvent(job, eventFlow, "Starting batch pipeline")

            // Run stages based on current state
            while (!job.isTerminal()) {
                when (job.state) {
                    BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT -> runCrawlAndExtractStage(job, eventFlow)
                    BatchPeriodicIndexJobState.BATCHING_CONTENT_LLM -> runContentBatchStage(job, eventFlow)
                    BatchPeriodicIndexJobState.BATCHING_FINAL_LLM -> runFinalBatchStage(job, eventFlow)
                    BatchPeriodicIndexJobState.WRITING_CACHE -> runCacheWriteStage(job, eventFlow)
                    else -> break
                }
            }

            if (job.state == BatchPeriodicIndexJobState.COMPLETED) {
                emitEvent(job, eventFlow, "Batch pipeline completed successfully")
            }
        } catch (e: Exception) {
            logger.error("Batch pipeline failed for job {}: {}", jobId, e.message, e)
            job.markFailed(e.message ?: "Unknown error")
            batchJobRepository.update(job)
            emitEvent(job, eventFlow, "Pipeline failed: ${e.message}")
        } finally {
            runs.remove(jobId)
        }
    }

    /**
     * Result of processing a single URL in stage 1.
     */
    private data class CrawlExtractResult(
        val url: String,
        val discoveredLinks: List<String>,
        val success: Boolean
    )

    /**
     * Stage 1: Combined crawl + extract with sliding window concurrency.
     * 
     * Uses flatMapMerge to maintain constant concurrency - as soon as one URL
     * completes processing, the next one starts immediately. This provides
     * higher throughput than batch processing where we wait for entire batches.
     * 
     * Flow:
     * - URLs are fed into a Channel
     * - flatMapMerge processes up to BROWSER_EXTRACTION_CONCURRENCY URLs in parallel
     * - Discovered links are fed back into the channel
     * - Channel closes when all work is complete (tracked via inFlightCount)
     */
    private suspend fun runCrawlAndExtractStage(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 1: Crawl + Extract for {} (sliding window concurrency: {})", 
            jobId, job.baseUrl, BROWSER_EXTRACTION_CONCURRENCY)
        emitEvent(job, eventFlow, "Stage 1: Crawling & extracting webpages...")

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
            emitEvent(job, eventFlow, "Stage 1 complete: No new URLs to process")
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
                    
                    val result = processSingleUrl(jobId, url, job)
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
                        emitEvent(job, eventFlow, "Processed $newCount/${job.maxUrlCount} URLs")
                    }
                }
            }

        // Final update
        batchJobRepository.update(job)
        
        logger.info("[{}] Stage 1 complete: Processed {} URLs", jobId, job.urlsProcessed)
        job.advanceToNextStage()
        batchJobRepository.update(job)
        emitEvent(job, eventFlow, "Stage 1 complete: ${job.urlsProcessed} pages crawled & extracted")
    }

    /**
     * Process a single URL: crawl + extract in one browser visit.
     * Returns discovered links for further processing.
     */
    private suspend fun processSingleUrl(
        jobId: Long,
        url: String,
        job: BatchPeriodicIndexJob
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
                browserPool.withPage { page ->
                    page.navigate(url)
                    val html = page.getFullHtml()
                    
                    // Parallel browser captures (same as WebpageExtractionService)
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

    /**
     * Stage 2: Submit PARALLEL batch jobs for semantic, table, icon, and image processing.
     * 
     * All batches are submitted at the same time for maximum parallelism.
     * Icons/images are deduplicated by hash across all URLs.
     * 
     * After image classification, images with tables get a second batch for table extraction.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun runContentBatchStage(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 2: Content LLM batch processing (parallel)", jobId)

        val urlsNeedingProcessing = batchUrlStateRepository.findNeedingContentLlmProcessing(jobId)
        if (urlsNeedingProcessing.isEmpty()) {
            logger.info("[{}] No URLs need content LLM processing, advancing", jobId)
            job.advanceToNextStage()
            batchJobRepository.update(job)
            return
        }

        emitEvent(job, eventFlow, "Stage 2: Submitting parallel content batches...")

        // Collect data for batch preparation via services
        val pagesForSemanticAndTable = mutableMapOf<Long, Pair<String, Map<String, io.deepsearch.domain.browser.IBrowserPage.BoundingBox>>>()
        val uniqueIcons = mutableMapOf<String, BatchIconData>() // hash -> icon data
        val uniqueImages = mutableMapOf<String, BatchImageData>() // hash -> image data
        val urlIconHashes = mutableMapOf<Long, List<String>>()
        val urlImageHashes = mutableMapOf<Long, List<String>>()

        urlsNeedingProcessing.forEach { urlState ->
            try {
                val snapshotData = urlState.snapshotData?.let { 
                    json.decodeFromString<BatchUrlSnapshotData>(it) 
                } ?: return@forEach

                val urlStateId = urlState.id!!

                // Collect page data for semantic and table identification
                pagesForSemanticAndTable[urlStateId] = snapshotData.html to (snapshotData.boundingBoxes ?: emptyMap())

                // Collect unique icons
                val iconHashes = mutableListOf<String>()
                snapshotData.icons?.forEach { icon ->
                    uniqueIcons.putIfAbsent(icon.hashBase64, icon)
                    iconHashes.add(icon.hashBase64)
                }
                urlIconHashes[urlStateId] = iconHashes

                // Collect unique images
                val imageHashes = mutableListOf<String>()
                snapshotData.images?.forEach { image ->
                    uniqueImages.putIfAbsent(image.hashBase64, image)
                    imageHashes.add(image.hashBase64)
                }
                urlImageHashes[urlStateId] = imageHashes

            } catch (e: Exception) {
                logger.warn("[{}] Failed to prepare batch requests for {}: {}", jobId, urlState.url, e.message)
            }
        }

        if (pagesForSemanticAndTable.isEmpty() && uniqueIcons.isEmpty() && uniqueImages.isEmpty()) {
            logger.info("[{}] No batch requests to submit, advancing", jobId)
            job.advanceToNextStage()
            batchJobRepository.update(job)
            return
        }

        // Prepare semantic identification batch using service (with cache check)
        val semanticBatchPrep = semanticIdentificationService.prepareBatchRequests(pagesForSemanticAndTable, jobId)
        val semanticRequests = semanticBatchPrep.batchRequests
        val semanticUrlIndexMap = semanticBatchPrep.requestIndexToUrlStateId
        val semanticHtmlMap = semanticBatchPrep.htmlWithIdsMap
        val cachedSemanticResults = semanticBatchPrep.cachedResults

        // Prepare table identification batch using service (with cache check)
        val tableBatchPrep = tableIdentificationService.prepareBatchRequests(pagesForSemanticAndTable, jobId)
        val tableRequests = tableBatchPrep.batchRequests
        val tableUrlIndexMap = tableBatchPrep.requestIndexToUrlStateId
        val tableHtmlMap = tableBatchPrep.htmlWithIdsMap
        val cachedTableResults = tableBatchPrep.cachedResults

        // Prepare icon interpretation batch using service (with cache check)
        val iconDataMap = uniqueIcons.mapValues { (_, iconData) ->
            val iconBytes = kotlin.io.encoding.Base64.decode(iconData.bytesBase64)
            iconBytes to iconData.mimeType
        }
        val iconBatchPrep = webpageIconInterpretationService.prepareBatchRequests(iconDataMap, jobId)
        val iconRequests = iconBatchPrep.batchRequests
        val iconHashIndexMap = iconBatchPrep.requestIndexToHash
        val cachedIconResults = iconBatchPrep.cachedResults.toMutableMap()

        // Prepare image classification batch using service (with cache check)
        val imageDataMap = uniqueImages.mapValues { (_, imageData) ->
            val imageBytes = kotlin.io.encoding.Base64.decode(imageData.bytesBase64)
            imageBytes to imageData.mimeType
        }
        val imageBatchPrep = webpageImageTextExtractionService.prepareBatchRequests(imageDataMap, jobId)
        val imageClassRequests = imageBatchPrep.classificationRequests
        val imageHashIndexMap = imageBatchPrep.classificationIndexToHash
        val cachedImageResults = imageBatchPrep.cachedResults.toMutableMap()

        // Apply cached semantic and table results immediately
        for ((urlStateId, semanticElements) in cachedSemanticResults) {
            val urlState = urlsNeedingProcessing.find { it.id == urlStateId } ?: continue
            val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) } ?: continue
            val updatedSnapshot = snapshotData.copy(
                cleanedHtml = semanticHtmlMap[urlStateId] ?: snapshotData.html,
                semanticElements = semanticElements
            )
            urlState.snapshotData = json.encodeToString(updatedSnapshot)
            batchUrlStateRepository.update(urlState)
        }

        for ((urlStateId, tableIdentifications) in cachedTableResults) {
            val urlState = urlsNeedingProcessing.find { it.id == urlStateId } ?: continue
            val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) } ?: continue
            val updatedSnapshot = snapshotData.copy(
                tableIdentifications = tableIdentifications
            )
            urlState.snapshotData = json.encodeToString(updatedSnapshot)
            batchUrlStateRepository.update(urlState)
        }

        logger.info("[{}] Preparing batches: {} semantic, {} table, {} icons, {} images", 
            jobId, semanticRequests.size, tableRequests.size, iconRequests.size, imageClassRequests.size)

        // Submit all batches
        val semanticBatchId = if (semanticRequests.isNotEmpty()) {
            geminiBatchService.createContentBatch(semanticRequests).also {
                logger.info("[{}] Submitted semantic batch: {}", jobId, it)
            }
        } else null

        val tableBatchId = if (tableRequests.isNotEmpty()) {
            geminiBatchService.createContentBatch(tableRequests).also {
                logger.info("[{}] Submitted table ID batch: {}", jobId, it)
            }
        } else null

        val iconBatchId = if (iconRequests.isNotEmpty()) {
            geminiBatchService.createContentBatch(iconRequests).also {
                logger.info("[{}] Submitted icon batch: {}", jobId, it)
            }
        } else null

        val imageClassBatchId = if (imageClassRequests.isNotEmpty()) {
            geminiBatchService.createContentBatch(imageClassRequests).also {
                logger.info("[{}] Submitted image classification batch: {}", jobId, it)
            }
        } else null

        emitEvent(job, eventFlow, "Submitted ${listOfNotNull(semanticBatchId, tableBatchId, iconBatchId, imageClassBatchId).size} parallel batches")

        // Poll all batches until completion
        pollMultipleBatchesUntilComplete(job, eventFlow, listOfNotNull(semanticBatchId, tableBatchId, iconBatchId, imageClassBatchId))

        // Process icon results (hash -> label) - start with cached results
        val iconInterpretations = cachedIconResults.toMutableMap()
        val newIconResults = mutableMapOf<String, String?>()
        if (iconBatchId != null) {
            val iconResults = geminiBatchService.fetchBatchResults(iconBatchId)
            iconResults.forEachIndexed { index, result ->
                val hash = iconHashIndexMap[index] ?: return@forEachIndexed
                val responseText = result.generatedText
                if (result.success && responseText != null) {
                    // Parse using agent's parseBatchResponse
                    val labels = webpageIconInterpretationService.let {
                        // Access the agent through reflection or use a simple JSON parse
                        try {
                            val jsonResponse = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                            val parsed = jsonResponse.decodeFromString<IconBatchResponseWrapper>(responseText)
                            parsed.icons.map { it.label }
                        } catch (e: Exception) {
                            logger.warn("[{}] Failed to parse icon response: {}", jobId, e.message)
                            emptyList()
                        }
                    }
                    val label = labels.firstOrNull()
                    iconInterpretations[hash] = label
                    newIconResults[hash] = label
                }
            }
            // Cache new icon results
            webpageIconInterpretationService.processBatchResults(newIconResults)
            logger.info("[{}] Processed {} icon interpretations ({} cached, {} new)", 
                jobId, iconInterpretations.size, cachedIconResults.size, newIconResults.size)
        }

        // Process image classification results - start with cached results
        val imageTexts = cachedImageResults.toMutableMap()
        val newImageResults = mutableMapOf<String, String?>()
        val imagesWithTables = mutableMapOf<String, Pair<ByteArray, String>>() // hash -> (bytes, mimeType)
        if (imageClassBatchId != null) {
            val imageResults = geminiBatchService.fetchBatchResults(imageClassBatchId)
            imageResults.forEachIndexed { index, result ->
                val hash = imageHashIndexMap[index] ?: return@forEachIndexed
                val responseText = result.generatedText
                if (result.success && responseText != null) {
                    // Parse classification response
                    try {
                        val jsonResponse = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        val classification = jsonResponse.decodeFromString<ImageClassificationResponseWrapper>(responseText)
                        if (classification.containsTable) {
                            // Need table extraction for this image
                            imageDataMap[hash]?.let { imagesWithTables[hash] = it }
                        } else {
                            val text = classification.text?.takeIf { it.isNotBlank() }
                            imageTexts[hash] = text
                            newImageResults[hash] = text
                        }
                    } catch (e: Exception) {
                        logger.warn("[{}] Failed to parse image classification: {}", jobId, e.message)
                    }
                }
            }
            logger.info("[{}] Processed image classifications: {} with text, {} need table extraction", 
                jobId, imageTexts.size - cachedImageResults.size, imagesWithTables.size)
        }

        // If any images contain tables, run table extraction batch using service
        if (imagesWithTables.isNotEmpty()) {
            val tableExtractPrep = webpageImageTextExtractionService.prepareTableExtractionBatchRequests(
                imagesWithTables, jobId
            )
            
            if (tableExtractPrep.tableExtractionRequests.isNotEmpty()) {
                val tableExtractBatchId = geminiBatchService.createContentBatch(tableExtractPrep.tableExtractionRequests)
                logger.info("[{}] Submitted image table extraction batch: {}", jobId, tableExtractBatchId)
                
                pollBatchUntilComplete(job, eventFlow, tableExtractBatchId)
                
                val extractResults = geminiBatchService.fetchBatchResults(tableExtractBatchId)
                extractResults.forEachIndexed { index, result ->
                    val hash = tableExtractPrep.requestIndexToHash[index] ?: return@forEachIndexed
                    if (result.success && result.generatedText != null) {
                        // Parse table extraction response
                        try {
                            val jsonResponse = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                            val extraction = jsonResponse.decodeFromString<TableExtractionResponseWrapper>(result.generatedText!!)
                            val text = extraction.text?.takeIf { it.isNotBlank() }
                            imageTexts[hash] = text
                            newImageResults[hash] = text
                        } catch (e: Exception) {
                            logger.warn("[{}] Failed to parse table extraction: {}", jobId, e.message)
                        }
                    }
                }
                logger.info("[{}] Extracted tables from {} images", jobId, extractResults.count { it.success })
            }
        }
        
        // Cache new image results
        if (newImageResults.isNotEmpty()) {
            webpageImageTextExtractionService.processBatchResults(newImageResults, imageDataMap)
        }

        // Process semantic results and update snapshots (cache + update)
        if (semanticBatchId != null) {
            val semanticResults = geminiBatchService.fetchBatchResults(semanticBatchId)
            semanticResults.forEachIndexed { index, result ->
                val urlStateId = semanticUrlIndexMap[index] ?: return@forEachIndexed
                val urlState = urlsNeedingProcessing.find { it.id == urlStateId } ?: return@forEachIndexed
                
                try {
                    val responseText = result.generatedText
                    if (!result.success || responseText == null) {
                        logger.warn("[{}] Semantic batch failed for {}: {}", jobId, urlState.url, result.errorMessage)
                        return@forEachIndexed
                    }

                    val htmlWithIds = semanticHtmlMap[urlStateId] ?: return@forEachIndexed
                    val semanticElements = semanticIdentificationService.parseBatchResponse(responseText, htmlWithIds)

                    // Cache the result
                    val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) } ?: return@forEachIndexed
                    val htmlHash = java.security.MessageDigest.getInstance("SHA-256").digest(snapshotData.html.toByteArray())
                    semanticIdentificationService.cacheResult(htmlHash, semanticElements)

                    val updatedSnapshot = snapshotData.copy(
                        cleanedHtml = htmlWithIds,
                        semanticElements = semanticElements
                    )
                    urlState.snapshotData = json.encodeToString(updatedSnapshot)
                    batchUrlStateRepository.update(urlState)

                } catch (e: Exception) {
                    logger.warn("[{}] Failed to process semantic result for {}: {}", jobId, urlState.url, e.message)
                }
            }
        }

        // Process table identification results (cache + update)
        if (tableBatchId != null) {
            val tableResults = geminiBatchService.fetchBatchResults(tableBatchId)
            tableResults.forEachIndexed { index, result ->
                val urlStateId = tableUrlIndexMap[index] ?: return@forEachIndexed
                val urlState = urlsNeedingProcessing.find { it.id == urlStateId } ?: return@forEachIndexed
                
                try {
                    val responseText = result.generatedText
                    if (!result.success || responseText == null) {
                        logger.warn("[{}] Table ID batch failed for {}: {}", jobId, urlState.url, result.errorMessage)
                        return@forEachIndexed
                    }

                    val htmlWithIds = tableHtmlMap[urlStateId] ?: return@forEachIndexed
                    val tableIdentifications = tableIdentificationService.parseBatchResponse(responseText, htmlWithIds)

                    // Cache the result
                    val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) } ?: return@forEachIndexed
                    val htmlHash = java.security.MessageDigest.getInstance("SHA-256").digest(snapshotData.html.toByteArray())
                    tableIdentificationService.cacheResult(htmlHash, tableIdentifications)

                    val updatedSnapshot = snapshotData.copy(tableIdentifications = tableIdentifications)
                    urlState.snapshotData = json.encodeToString(updatedSnapshot)
                    batchUrlStateRepository.update(urlState)

                } catch (e: Exception) {
                    logger.warn("[{}] Failed to process table ID result for {}: {}", jobId, urlState.url, e.message)
                }
            }
        }

        // Store icon/image interpretations back to each URL's snapshot
        urlsNeedingProcessing.forEach { urlState ->
            try {
                val snapshotData = urlState.snapshotData?.let {
                    json.decodeFromString<BatchUrlSnapshotData>(it)
                } ?: return@forEach

                val urlStateId = urlState.id!!
                val urlIconHashList = urlIconHashes[urlStateId] ?: emptyList()
                val urlImageHashList = urlImageHashes[urlStateId] ?: emptyList()

                // Build icon interpretations for this URL
                val urlIconInterpretations = urlIconHashList.mapNotNull { hash ->
                    iconInterpretations[hash]?.let { hash to it }
                }.toMap()

                // Build image texts for this URL
                val urlImageTexts = urlImageHashList.mapNotNull { hash ->
                    imageTexts[hash]?.let { hash to it }
                }.toMap()

                if (urlIconInterpretations.isNotEmpty() || urlImageTexts.isNotEmpty()) {
                    val updatedSnapshot = snapshotData.copy(
                        iconInterpretations = urlIconInterpretations.takeIf { it.isNotEmpty() },
                        imageTexts = urlImageTexts.takeIf { it.isNotEmpty() }
                    )
                    urlState.snapshotData = json.encodeToString(updatedSnapshot)
                    batchUrlStateRepository.update(urlState)
                }

            } catch (e: Exception) {
                logger.warn("[{}] Failed to store icon/image results for {}: {}", jobId, urlState.url, e.message)
            }
        }

        // Mark all URLs as content LLM done and advance
        val urlsToMark = batchUrlStateRepository.findNeedingContentLlmProcessing(jobId)
        urlsToMark.forEach { urlState ->
            if (!urlState.isFailed()) {
                urlState.markContentLlmDone(urlState.snapshotData ?: "")
                batchUrlStateRepository.update(urlState)
            }
        }

        job.urlsContentProcessed = urlsToMark.count { !it.isFailed() }
        job.advanceToNextStage()
        batchJobRepository.update(job)
        emitEvent(job, eventFlow, "Stage 2 complete: ${job.urlsContentProcessed} pages analyzed")
    }

    /**
     * Poll multiple batch jobs until all complete.
     * Throws on failure of any batch.
     */
    private suspend fun pollMultipleBatchesUntilComplete(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        batchIds: List<String>
    ) {
        if (batchIds.isEmpty()) return
        
        val completedBatches = mutableSetOf<String>()
        var attempts = 0

        while (completedBatches.size < batchIds.size && attempts < MAX_BATCH_POLL_ATTEMPTS) {
            try {
                batchIds.forEach { batchId ->
                    if (batchId in completedBatches) return@forEach
                    
                    val status = geminiBatchService.pollBatchStatus(batchId)
                    when (status.state) {
                        BatchJobState.SUCCEEDED -> {
                            logger.info("Batch {} completed", batchId)
                            completedBatches.add(batchId)
                        }
                        BatchJobState.FAILED -> throw RuntimeException("Batch $batchId failed: ${status.errorMessage}")
                        BatchJobState.CANCELLED -> throw RuntimeException("Batch $batchId was cancelled")
                        else -> {}
                    }
                }

                if (completedBatches.size < batchIds.size) {
                    emitEvent(job, eventFlow, "Waiting for batches (${completedBatches.size}/${batchIds.size} complete)")
                    delay(BATCH_POLL_INTERVAL_MS)
                }
            } catch (e: Exception) {
                if (e.message?.contains("429") == true) {
                    logger.warn("Rate limited while polling, will retry")
                    delay(BATCH_POLL_INTERVAL_MS)
                } else {
                    throw e
                }
            }
            
            attempts++
        }

        if (completedBatches.size < batchIds.size) {
            throw RuntimeException("Batch polling timed out after $MAX_BATCH_POLL_ATTEMPTS attempts")
        }
    }

    /**
     * Stage 3: Apply icon/image replacements, then submit batch job for table interpretation.
     * 
     * Following the same order as WebpageExtractionService:
     * 1. Inject media identifiers (icons + images)
     * 2. Inject semantic identifiers
     * 3. Inject table identifiers
     * 4. Apply media replacements (icons + images) 
     * 5. Extract popup text before removal
     * 6. Remove semantic elements
     * 7. Interpret tables (with modified HTML containing replaced icons/images)
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun runFinalBatchStage(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 3: Final LLM batch processing (table interpretation)", jobId)
        emitEvent(job, eventFlow, "Stage 3: Applying media replacements and preparing table interpretation...")

        // Check if we already have a batch job running
        if (job.geminiBatchJobId != null) {
            pollBatchUntilComplete(job, eventFlow, job.geminiBatchJobId!!)
            processTableBatchResults(jobId, job.geminiBatchJobId!!)
            job.clearBatchJob()
            job.advanceToNextStage()
            batchJobRepository.update(job)
            return
        }

        val urlsNeedingProcessing = batchUrlStateRepository.findNeedingFinalLlmProcessing(jobId)
        if (urlsNeedingProcessing.isEmpty()) {
            logger.info("[{}] No URLs need final LLM processing, advancing", jobId)
            job.advanceToNextStage()
            batchJobRepository.update(job)
            return
        }

        // Collect table interpretation inputs for batch preparation via service
        val tableInputs = mutableListOf<TableInterpretationBatchInput>()

        urlsNeedingProcessing.forEach { urlState ->
            try {
                val snapshotData = urlState.snapshotData?.let {
                    json.decodeFromString<BatchUrlSnapshotData>(it)
                } ?: return@forEach
                
                // Start with cleaned HTML that has semantic IDs injected
                val cleanedHtml = snapshotData.cleanedHtml ?: snapshotData.html
                val doc = Jsoup.parse(cleanedHtml)
                
                // Step 1: Inject media identifiers (same as WebpageExtractionService)
                jsoupDomService.injectMediaIdentifiers(doc)
                
                // Step 2: Build and apply icon/image replacements
                val iconReplacements = mutableListOf<CssSelectorReplacement>()
                val imageReplacements = mutableListOf<CssSelectorReplacement>()
                
                // Build icon replacements using hash -> label mapping
                snapshotData.icons?.forEach { iconData ->
                    val label = snapshotData.iconInterpretations?.get(iconData.hashBase64)
                    if (label != null) {
                        iconData.cssSelectors.forEach { selector ->
                            iconReplacements.add(CssSelectorReplacement(selector, label))
                        }
                    }
                }
                
                // Build image replacements using hash -> text mapping (with XML wrapper)
                snapshotData.images?.forEach { imageData ->
                    val text = snapshotData.imageTexts?.get(imageData.hashBase64)
                    if (text != null) {
                        val imageId = generateImageId(imageData.hashBase64)
                        val wrappedText = if (text.contains('\n')) {
                            "<image id=\"$imageId\">\n$text\n</image>"
                        } else {
                            "<image id=\"$imageId\">$text</image>"
                        }
                        imageData.cssSelectors.forEach { selector ->
                            imageReplacements.add(CssSelectorReplacement(selector, wrappedText))
                        }
                    }
                }
                
                // Step 3: Apply media replacements (icons + images) BEFORE table interpretation
                val allMediaReplacements = iconReplacements + imageReplacements
                if (allMediaReplacements.isNotEmpty()) {
                    jsoupDomService.replaceElementsWithText(doc, allMediaReplacements)
                    logger.debug("[{}] Applied {} media replacements for {}", 
                        jobId, allMediaReplacements.size, urlState.url)
                }
                
                // Step 4: Get tables for interpretation
                val tables = snapshotData.tableIdentifications ?: emptyList()
                
                if (tables.isEmpty()) {
                    // Update snapshot with processed HTML (media replaced)
                    val updatedSnapshot = snapshotData.copy(tableMarkdowns = emptyMap())
                    urlState.markFinalLlmDone(json.encodeToString(updatedSnapshot))
                    batchUrlStateRepository.update(urlState)
                    return@forEach
                }

                // Derive bounding boxes for all tables from the page bounding boxes
                val pageBoundingBoxes = snapshotData.boundingBoxes ?: emptyMap()
                val derivedDataMap = boundingBoxDerivationService.deriveElementsBoundingBoxes(
                    cssSelectors = tables.map { it.cssSelector },
                    html = snapshotData.html, // Use original HTML for derivation
                    pageBoundingBoxes = pageBoundingBoxes
                )
                
                tables.forEach { table ->
                    // Get table HTML from the modified doc (with media replacements applied)
                    val tableElement = doc.select("[data-ds-id=\"${table.dataId}\"]").firstOrNull()
                    val tableHtml = tableElement?.outerHtml() ?: return@forEach
                    
                    // Get derived bounding boxes for this table
                    val derivedData = derivedDataMap[table.cssSelector]
                    val tableBoundingBoxes = derivedData?.boundingBoxes ?: emptyMap()
                    
                    tableInputs.add(TableInterpretationBatchInput(
                        urlStateId = urlState.id!!,
                        tableDataId = table.dataId,
                        tableHtml = tableHtml,
                        auxiliaryInfo = table.auxiliaryInfo,
                        boundingBoxes = tableBoundingBoxes
                    ))
                }
                
            } catch (e: Exception) {
                logger.warn("[{}] Failed to prepare table batch for {}: {}", jobId, urlState.url, e.message)
            }
        }

        // Use service to prepare batch requests (with cache check)
        val tableBatchPrep = tableInterpretationService.prepareBatchRequests(tableInputs, jobId)
        val batchRequests = tableBatchPrep.batchRequests
        val cachedTableResults = tableBatchPrep.cachedResults

        // Apply cached results immediately
        val urlTableMarkdowns = mutableMapOf<Long, MutableMap<String, String>>()
        for ((key, markdown) in cachedTableResults) {
            val (urlStateId, tableDataId) = key
            urlTableMarkdowns.getOrPut(urlStateId) { mutableMapOf() }[tableDataId] = markdown
        }

        // Update URL states with cached results
        for ((urlStateId, tableMarkdowns) in urlTableMarkdowns) {
            val urlState = urlsNeedingProcessing.find { it.id == urlStateId } ?: continue
            val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) } ?: continue
            
            // Check if all tables for this URL are cached
            val allTables = snapshotData.tableIdentifications ?: emptyList()
            val allTablesCached = allTables.all { tableMarkdowns.containsKey(it.dataId) }
            
            if (allTablesCached) {
                val updatedSnapshot = snapshotData.copy(tableMarkdowns = tableMarkdowns)
                urlState.markFinalLlmDone(json.encodeToString(updatedSnapshot))
                batchUrlStateRepository.update(urlState)
            }
        }

        if (batchRequests.isEmpty()) {
            urlsNeedingProcessing.forEach { urlState ->
                if (urlState.stage != io.deepsearch.domain.models.entities.BatchUrlProcessingStage.FINAL_LLM_DONE) {
                    val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) }
                    if (snapshotData != null) {
                        urlState.markFinalLlmDone(json.encodeToString(snapshotData.copy(tableMarkdowns = emptyMap())))
                        batchUrlStateRepository.update(urlState)
                    }
                }
            }
            job.urlsFinalProcessed = urlsNeedingProcessing.size
            job.advanceToNextStage()
            batchJobRepository.update(job)
            emitEvent(job, eventFlow, "Stage 3 complete: All tables cached or no tables to interpret")
            return
        }

        // Build request mapping from service's indexing
        val requestMapping = tableBatchPrep.requestIndexToKey.entries.map { (index, key) ->
            Triple(key.first, key.second, index)
        }

        logger.info("[{}] Submitting {} table interpretation requests ({} cached)", 
            jobId, batchRequests.size, cachedTableResults.size)

        tableInterpretationMappings[jobId] = requestMapping

        try {
            val batchJobId = geminiBatchService.createContentBatch(batchRequests)
            job.setBatchJob(batchJobId)
            batchJobRepository.update(job)
            
            logger.info("[{}] Submitted table batch job: {}", jobId, batchJobId)
            emitEvent(job, eventFlow, "Table batch submitted: $batchJobId (${batchRequests.size} tables)")
            
            pollBatchUntilComplete(job, eventFlow, batchJobId)
            processTableBatchResults(jobId, batchJobId)
            
            job.clearBatchJob()
            job.advanceToNextStage()
            batchJobRepository.update(job)
            
        } catch (e: Exception) {
            logger.error("[{}] Failed to submit table batch: {}", jobId, e.message, e)
            throw e
        }
    }
    
    /**
     * Generate an image ID from a hash (same format as WebpageExtractionService).
     */
    private fun generateImageId(hashBase64: String): String {
        val urlSafeHash = hashBase64.replace("+", "-").replace("/", "_").trimEnd('=')
        return "img-$urlSafeHash"
    }

    private val tableInterpretationMappings = ConcurrentHashMap<Long, List<Triple<Long, String, Int>>>()

    private suspend fun processTableBatchResults(jobId: Long, batchJobId: String) {
        logger.info("[{}] Processing table batch results from: {}", jobId, batchJobId)
        
        val results = geminiBatchService.fetchBatchResults(batchJobId)
        val mappings = tableInterpretationMappings.remove(jobId) ?: emptyList()
        
        val resultsByUrlState = mutableMapOf<Long, MutableMap<String, String>>()
        
        // Get the batch preparation to access hash map for caching
        val urlsNeedingProcessingForHash = batchUrlStateRepository.findNeedingFinalLlmProcessing(jobId)

        mappings.forEach { (urlStateId, tableDataId, requestIndex) ->
            if (requestIndex >= results.size) return@forEach
            
            val result = results[requestIndex]
            val responseText = result.generatedText
            if (result.success && responseText != null) {
                val tableMarkdowns = resultsByUrlState.getOrPut(urlStateId) { mutableMapOf() }
                val markdown = tableInterpretationService.parseBatchResponse(responseText)
                tableMarkdowns[tableDataId] = markdown
                
                // Find the table HTML hash and cache the result
                val urlState = urlsNeedingProcessingForHash.find { it.id == urlStateId }
                if (urlState != null) {
                    val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) }
                    val table = snapshotData?.tableIdentifications?.find { it.dataId == tableDataId }
                    if (table != null && snapshotData != null) {
                        val cleanedHtml = snapshotData.cleanedHtml ?: snapshotData.html
                        val doc = Jsoup.parse(cleanedHtml)
                        val tableElement = doc.select("[data-ds-id=\"${table.dataId}\"]").firstOrNull()
                        val tableHtml = tableElement?.outerHtml()
                        if (tableHtml != null) {
                            val tableHash = java.security.MessageDigest.getInstance("SHA-256").digest(tableHtml.toByteArray())
                            tableInterpretationService.cacheResult(tableHash, markdown)
                        }
                    }
                }
            }
        }
        
        // Re-fetch to get current state after caching operations
        val urlsToUpdate = batchUrlStateRepository.findNeedingFinalLlmProcessing(jobId)
        
        urlsToUpdate.forEach { urlState ->
            try {
                val snapshotData = urlState.snapshotData?.let {
                    json.decodeFromString<BatchUrlSnapshotData>(it)
                } ?: return@forEach
                
                val tableMarkdowns = resultsByUrlState[urlState.id] ?: emptyMap()
                
                val updatedSnapshot = snapshotData.copy(tableMarkdowns = tableMarkdowns)
                urlState.markFinalLlmDone(json.encodeToString(updatedSnapshot))
                batchUrlStateRepository.update(urlState)
                
                logger.debug("[{}] Table interpretation complete for {}: {} tables", 
                    jobId, urlState.url, tableMarkdowns.size)
                    
            } catch (e: Exception) {
                logger.warn("[{}] Failed to process table results for {}: {}", jobId, urlState.url, e.message)
                urlState.markFailed("Failed to process table results: ${e.message}")
                batchUrlStateRepository.update(urlState)
            }
        }
    }

    /**
     * Stage 4: Write cache and generate embeddings using batch API.
     */
    private suspend fun runCacheWriteStage(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 4: Writing cache and generating embeddings", jobId)
        emitEvent(job, eventFlow, "Stage 4: Saving results to cache...")

        val urlsNeedingCaching = batchUrlStateRepository.findNeedingCaching(jobId)
        logger.info("[{}] {} URLs need caching", jobId, urlsNeedingCaching.size)

        if (urlsNeedingCaching.isEmpty()) {
            job.markCompleted()
            batchJobRepository.update(job)
            emitEvent(job, eventFlow, "Stage 4 complete: No URLs to cache")
            return
        }

        val sessionId = PeriodicIndexSessionId(jobId)

        val embeddingRequests = mutableListOf<BatchEmbeddingRequest>()
        val urlMarkdownMap = mutableMapOf<Long, String>()

        urlsNeedingCaching.forEach { urlState ->
            try {
                val snapshotData = urlState.snapshotData?.let { 
                    json.decodeFromString<BatchUrlSnapshotData>(it) 
                }
                
                val markdown = buildFinalMarkdown(urlState, snapshotData)
                urlMarkdownMap[urlState.id!!] = markdown

                val requestId = "${jobId}-${urlState.id}-embed"
                embeddingRequests.add(BatchEmbeddingRequest(
                    requestId = requestId,
                    modelId = "gemini-embedding-001",
                    text = markdown,
                    taskType = "RETRIEVAL_DOCUMENT",
                    outputDimensionality = 1536
                ))
                
            } catch (e: Exception) {
                logger.warn("[{}] Failed to prepare markdown for {}: {}", jobId, urlState.url, e.message)
            }
        }

        if (embeddingRequests.isNotEmpty()) {
            try {
                emitEvent(job, eventFlow, "Generating embeddings for ${embeddingRequests.size} pages...")
                
                val embeddingBatchId = geminiBatchService.createEmbeddingBatch(embeddingRequests)
                job.setBatchJob(embeddingBatchId)
                batchJobRepository.update(job)
                
                logger.info("[{}] Submitted embedding batch: {}", jobId, embeddingBatchId)
                
                try {
                    pollBatchUntilComplete(job, eventFlow, embeddingBatchId)
                } catch (e: Exception) {
                    logger.warn("[{}] Embedding batch failed, continuing without embeddings: {}", jobId, e.message)
                } finally {
                    job.clearBatchJob()
                    batchJobRepository.update(job)
                }
                
            } catch (e: Exception) {
                logger.warn("[{}] Embedding batch failed: {}, continuing without embeddings", jobId, e.message)
            }
        }

        urlsNeedingCaching.forEachIndexed { _, urlState ->
            try {
                val snapshotData = urlState.snapshotData?.let { 
                    json.decodeFromString<BatchUrlSnapshotData>(it) 
                }
                
                val markdown = urlMarkdownMap[urlState.id] ?: buildFinalMarkdown(urlState, snapshotData)

                webpageCacheService.cacheWebpage(
                    url = urlState.url,
                    title = urlState.title,
                    description = urlState.description,
                    markdown = markdown,
                    html = snapshotData?.html,
                    httpStatus = 200,
                    httpReason = "OK",
                    mimeType = "text/html",
                    sessionId = sessionId
                )

                urlState.markCached()
                batchUrlStateRepository.update(urlState)
                job.urlsCached++
                
            } catch (e: Exception) {
                logger.warn("[{}] Failed to cache {}: {}", jobId, urlState.url, e.message)
                urlState.markFailed(e.message ?: "Caching failed")
                batchUrlStateRepository.update(urlState)
            }
        }

        batchJobRepository.update(job)
        
        job.markCompleted()
        batchJobRepository.update(job)
        emitEvent(job, eventFlow, "Stage 4 complete: ${job.urlsCached} pages cached")
    }

    private fun buildFinalMarkdown(urlState: BatchUrlState, snapshotData: BatchUrlSnapshotData?): String {
        if (snapshotData == null) {
            return buildString {
                appendLine("URL: ${urlState.url}")
                appendLine("Title: ${urlState.title ?: "Unknown"}")
                appendLine()
                appendLine("Content extracted via batch processing.")
            }
        }

        val existingMarkdown = snapshotData.markdown
        if (existingMarkdown != null) {
            return existingMarkdown
        }

        val html = snapshotData.cleanedHtml ?: snapshotData.html
        val doc = Jsoup.parse(html)
        
        // Step 1: Inject media identifiers (same as WebpageExtractionService)
        jsoupDomService.injectMediaIdentifiers(doc)
        
        // Step 2: Apply icon/image replacements
        val iconReplacements = mutableListOf<CssSelectorReplacement>()
        val imageReplacements = mutableListOf<CssSelectorReplacement>()
        
        snapshotData.icons?.forEach { iconData ->
            val label = snapshotData.iconInterpretations?.get(iconData.hashBase64)
            if (label != null) {
                iconData.cssSelectors.forEach { selector ->
                    iconReplacements.add(CssSelectorReplacement(selector, label))
                }
            }
        }
        
        snapshotData.images?.forEach { imageData ->
            val text = snapshotData.imageTexts?.get(imageData.hashBase64)
            if (text != null) {
                val imageId = generateImageId(imageData.hashBase64)
                val wrappedText = if (text.contains('\n')) {
                    "<image id=\"$imageId\">\n$text\n</image>"
                } else {
                    "<image id=\"$imageId\">$text</image>"
                }
                imageData.cssSelectors.forEach { selector ->
                    imageReplacements.add(CssSelectorReplacement(selector, wrappedText))
                }
            }
        }
        
        val allMediaReplacements = iconReplacements + imageReplacements
        if (allMediaReplacements.isNotEmpty()) {
            jsoupDomService.replaceElementsWithText(doc, allMediaReplacements)
        }
        
        // Step 3: Remove semantic elements
        snapshotData.semanticElements?.let { semantic ->
            listOfNotNull(
                semantic.header?.dataId,
                semantic.footer?.dataId,
                semantic.navSidebar?.dataId,
                semantic.breadcrumb?.dataId,
                semantic.cookieBanner?.dataId
            ).plus(semantic.adBanners.map { it.dataId })
             .plus(semantic.popups.map { it.dataId })
             .forEach { dataId ->
                 doc.select("[data-ds-id=\"$dataId\"]").remove()
             }
        }
        
        // Step 4: Replace tables with markdown
        snapshotData.tableMarkdowns?.forEach { (dataId, markdown) ->
            val tableElement = doc.select("[data-ds-id=\"$dataId\"]").firstOrNull()
            tableElement?.html(markdown)
        }

        val textContent = jsoupDomService.extractTextContent(doc)

        return buildString {
            appendLine("URL: ${urlState.url}")
            appendLine("Title: ${urlState.title ?: "Unknown"}")
            if (!urlState.description.isNullOrBlank()) {
                appendLine("Description: ${urlState.description}")
            }
            appendLine()
            appendLine(textContent)
        }.trim()
    }

    /**
     * Poll a single batch job until it completes.
     * Throws on failure or timeout.
     */
    private suspend fun pollBatchUntilComplete(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        batchJobId: String
    ) {
        var attempts = 0

        while (attempts < MAX_BATCH_POLL_ATTEMPTS) {
            try {
                val status = geminiBatchService.pollBatchStatus(batchJobId)
                
                when (status.state) {
                    BatchJobState.SUCCEEDED -> {
                        logger.info("Batch job {} completed successfully", batchJobId)
                        return
                    }
                    BatchJobState.FAILED -> {
                        throw RuntimeException("Batch job failed: ${status.errorMessage}")
                    }
                    BatchJobState.CANCELLED -> {
                        throw RuntimeException("Batch job was cancelled")
                    }
                    else -> {
                        emitEvent(job, eventFlow, 
                            "Waiting for batch (${status.completedRequests}/${status.totalRequests})")
                    }
                }
            } catch (e: Exception) {
                if (e.message?.contains("429") == true) {
                    logger.warn("Rate limited while polling, will retry")
                } else {
                    throw e
                }
            }

            delay(BATCH_POLL_INTERVAL_MS)
            attempts++
        }

        throw RuntimeException("Batch job polling timed out after $MAX_BATCH_POLL_ATTEMPTS attempts")
    }

    private fun matchesLanguageFilter(url: String, job: BatchPeriodicIndexJob): Boolean {
        val pattern = job.languagePattern?.let { LanguagePattern.parse(it) } ?: return true
        return pattern.matches(url, job.baseUrl)
    }

    private suspend fun emitEvent(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        message: String
    ) {
        val counts = try {
            batchUrlStateRepository.countByStage(requireNotNull(job.id))
        } catch (e: Exception) {
            io.deepsearch.domain.repositories.BatchUrlStageCounts(0, 0, 0, 0, 0, 0, 0)
        }

        eventFlow.emit(
            BatchPeriodicIndexEvent(
                jobId = job.id!!,
                baseUrl = job.baseUrl,
                state = job.state,
                stage = job.currentStage(),
                stageDescription = job.stageDescription(),
                urlsProcessed = job.urlsProcessed,
                urlsContentProcessed = job.urlsContentProcessed,
                urlsFinalProcessed = job.urlsFinalProcessed,
                urlsCached = job.urlsCached,
                totalUrls = counts.total,
                geminiBatchJobId = job.geminiBatchJobId,
                errorMessage = job.errorMessage,
                message = message
            )
        )
    }
}
