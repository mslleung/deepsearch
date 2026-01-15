package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IVisualIdentificationService
import io.deepsearch.application.services.IWebpageIconInterpretationService
import io.deepsearch.application.services.IWebpageImageTextExtractionService
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.BatchUrlStateId
import io.deepsearch.domain.models.valueobjects.MediaHash
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.BatchJobState
import io.deepsearch.domain.services.IGeminiBatchService
import io.deepsearch.domain.services.IJsoupDomService
import io.deepsearch.domain.services.IBatchSnapshotStorageService
import io.deepsearch.domain.services.ContentLlmResults
import org.jsoup.Jsoup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Stage 2: Content LLM batch handler.
 * 
 * Submits parallel batch jobs for:
 * - Visual identification (semantic elements + tables in single call)
 * - Icon interpretation
 * - Image classification (with follow-up table extraction for images containing tables)
 * 
 * Icons and images are deduplicated by hash across all URLs.
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class ContentLlmBatchHandler(
    private val batchJobRepository: IBatchPeriodicIndexJobRepository,
    private val batchUrlStateRepository: IBatchUrlStateRepository,
    private val geminiBatchService: IGeminiBatchService,
    private val jsoupDomService: IJsoupDomService,
    private val visualIdentificationService: IVisualIdentificationService,
    private val webpageIconInterpretationService: IWebpageIconInterpretationService,
    private val webpageImageTextExtractionService: IWebpageImageTextExtractionService,
    private val snapshotStorage: IBatchSnapshotStorageService,
    private val eventEmitter: BatchEventEmitter,
    private val batchTokenUsageRecorder: BatchTokenUsageRecorder
) : IBatchStageHandler {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    companion object {
        private const val BATCH_POLL_INTERVAL_MS = 60_000L
        private const val MAX_BATCH_POLL_ATTEMPTS = 1440
    }

    override suspend fun execute(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 2: Content LLM batch processing", jobId)

        val urlStates = batchUrlStateRepository.findNeedingContentLlmProcessing(jobId)
        if (urlStates.isEmpty()) {
            logger.info("[{}] No URLs need content LLM processing, advancing", jobId)
            job.advanceToNextStage()
            batchJobRepository.update(job)
            return
        }

        eventEmitter.emit(job, eventFlow, "Stage 2: Submitting parallel content batches...")

        // 1. Collect data from URL snapshots
        val collected = collectContentData(urlStates)
        if (collected.isEmpty()) {
            logger.info("[{}] No batch requests to submit, advancing", jobId)
            job.advanceToNextStage()
            batchJobRepository.update(job)
            return
        }

        // 2. Prepare all batch requests (with cache checks)
        val preparations = prepareBatches(collected, jobId)

        // 3. Apply cached results immediately
        applyCachedResults(preparations, urlStates, collected)

        // 4. Submit and poll batches
        val batches = submitBatches(preparations, jobId)
        eventEmitter.emit(job, eventFlow, "Submitted ${batches.count()} parallel batches")
        pollMultipleBatchesUntilComplete(job, eventFlow, batches.allIds())

        // 5. Process media results (icons + images)
        val mediaResults = processMediaResults(batches, preparations, job, eventFlow, jobId)

        // 6. Process page results (semantic + tables)
        processPageResults(batches, preparations, urlStates, collected, jobId)

        // 7. Store media interpretations back to URLs
        storeMediaResultsToUrls(urlStates, collected, mediaResults)

        // 8. Finalize stage
        finalizeContentStage(job, eventFlow, jobId)
    }

    // ==================== Phase 1: Collect Data ====================

    /**
     * Collect content data from GCS storage for all URL states.
     * Reads HTML, icons, images, and screenshots from GCS instead of database.
     */
    private suspend fun collectContentData(urlStates: List<BatchUrlState>): ContentCollectionResult {
        val urlPages = mutableMapOf<BatchUrlStateId, UrlPageData>()
        val uniqueIcons = mutableMapOf<MediaHash, GcsIconData>()
        val uniqueImages = mutableMapOf<MediaHash, GcsImageData>()

        urlStates.forEach { urlState ->
            try {
                val basePath = urlState.snapshotBasePath ?: return@forEach
                val urlStateId = BatchUrlStateId(urlState.id!!)

                // Read HTML from GCS
                val html = snapshotStorage.readHtml(basePath) ?: return@forEach
                
                // Read bounding boxes from GCS
                val boundingBoxes = snapshotStorage.readBoundingBoxes(basePath) ?: emptyMap()
                
                // Read screenshot from GCS
                val screenshot = snapshotStorage.readScreenshot(basePath)
                
                // Read icons from GCS
                val icons = snapshotStorage.readIcons(basePath)
                val iconHashes = mutableListOf<MediaHash>()
                icons.forEach { icon ->
                    val hash = MediaHash(icon.hash)
                    uniqueIcons.putIfAbsent(hash, GcsIconData(icon.bytes, icon.mimeType, icon.cssSelectors))
                    iconHashes.add(hash)
                }

                // Read images from GCS
                val images = snapshotStorage.readImages(basePath)
                val imageHashes = mutableListOf<MediaHash>()
                images.forEach { image ->
                    val hash = MediaHash(image.hash)
                    uniqueImages.putIfAbsent(hash, GcsImageData(image.bytes, image.mimeType, image.cssSelectors))
                    imageHashes.add(hash)
                }

                urlPages[urlStateId] = UrlPageData(
                    urlStateId = urlStateId,
                    basePath = basePath,
                    html = html,
                    boundingBoxes = boundingBoxes,
                    iconHashes = iconHashes,
                    imageHashes = imageHashes,
                    screenshotBytes = screenshot?.bytes,
                    screenshotMimeType = screenshot?.mimeType
                )
            } catch (e: Exception) {
                logger.warn("Failed to collect data for {}: {}", urlState.url, e.message)
            }
        }

        return ContentCollectionResult(urlPages, uniqueIcons, uniqueImages)
    }

    // ==================== Phase 2: Prepare Batches ====================

    private data class BatchPreparations(
        val visualPrep: io.deepsearch.application.services.VisualIdentificationBatchPreparation,
        val iconPrep: io.deepsearch.application.services.IconBatchPreparation,
        val imagePrep: io.deepsearch.application.services.ImageBatchPreparation,
        val iconDataMap: Map<MediaHash, MediaData>,
        val imageDataMap: Map<MediaHash, MediaData>
    )

    private suspend fun prepareBatches(collected: ContentCollectionResult, jobId: Long): BatchPreparations {
        val pagesForServices = collected.pagesForBatchServices()

        // Prepare combined visual identification batch (semantic + tables in single call)
        val visualPrep = visualIdentificationService.prepareBatchRequests(pagesForServices, jobId)

        // Prepare icon interpretation batch (bytes already loaded from GCS)
        val iconDataMap = collected.uniqueIcons.mapValues { (_, iconData) ->
            MediaData(iconData.bytes, iconData.mimeType)
        }
        val iconPrep = webpageIconInterpretationService.prepareBatchRequests(iconDataMap, jobId)

        // Prepare image classification batch (bytes already loaded from GCS)
        val imageDataMap = collected.uniqueImages.mapValues { (_, imageData) ->
            MediaData(imageData.bytes, imageData.mimeType)
        }
        val imagePrep = webpageImageTextExtractionService.prepareBatchRequests(imageDataMap, jobId)

        logger.info("[{}] Prepared batches: {} visual (semantic+tables), {} icons, {} images",
            jobId, visualPrep.pendingRequests.size,
            iconPrep.pendingRequests.size, imagePrep.pendingRequests.size)

        return BatchPreparations(visualPrep, iconPrep, imagePrep, iconDataMap, imageDataMap)
    }

    // ==================== Phase 3: Apply Cached Results ====================

    private suspend fun applyCachedResults(
        preparations: BatchPreparations, 
        urlStates: List<BatchUrlState>,
        collected: ContentCollectionResult
    ) {
        // Apply cached visual identification results (semantic + tables combined)
        for ((urlStateId, visualResult) in preparations.visualPrep.cachedResults) {
            val urlState = urlStates.find { it.id == urlStateId.value } ?: continue
            val basePath = urlState.snapshotBasePath ?: continue
            val pageData = collected.urlPages[urlStateId] ?: continue
            
            // Inject both semantic and table IDs into HTML
            val doc = Jsoup.parse(pageData.html)
            
            // Inject semantic element IDs
            val semanticInjections = buildList {
                visualResult.semanticElements.header?.let { add(it.cssSelector to it.dataId) }
                visualResult.semanticElements.footer?.let { add(it.cssSelector to it.dataId) }
                visualResult.semanticElements.navSidebar?.let { add(it.cssSelector to it.dataId) }
                visualResult.semanticElements.breadcrumb?.let { add(it.cssSelector to it.dataId) }
                visualResult.semanticElements.cookieBanner?.let { add(it.cssSelector to it.dataId) }
                addAll(visualResult.semanticElements.adBanners.map { it.cssSelector to it.dataId })
                addAll(visualResult.semanticElements.popups.map { it.cssSelector to it.dataId })
            }
            jsoupDomService.injectIdentifiers(doc, semanticInjections)
            
            // Inject table IDs
            val tableInjections = visualResult.tables.map { it.cssSelector to it.dataId }
            jsoupDomService.injectIdentifiers(doc, tableInjections)
            
            // Store results to GCS
            snapshotStorage.storeContentLlmResults(
                basePath,
                ContentLlmResults(
                    cleanedHtml = doc.html(),
                    semanticElements = visualResult.semanticElements,
                    tableIdentifications = visualResult.tables,
                    iconInterpretations = null,
                    imageTexts = null
                )
            )
        }
    }

    // ==================== Phase 4: Submit Batches ====================

    private suspend fun submitBatches(preparations: BatchPreparations, jobId: Long): SubmittedBatches {
        val visualId = if (preparations.visualPrep.pendingRequests.isNotEmpty()) {
            geminiBatchService.createContentBatch(preparations.visualPrep.pendingRequests.map { it.request }).also {
                logger.info("[{}] Submitted visual identification batch: {}", jobId, it)
            }
        } else null

        val iconId = if (preparations.iconPrep.pendingRequests.isNotEmpty()) {
            geminiBatchService.createContentBatch(preparations.iconPrep.pendingRequests.map { it.request }).also {
                logger.info("[{}] Submitted icon batch: {}", jobId, it)
            }
        } else null

        val imageClassId = if (preparations.imagePrep.pendingRequests.isNotEmpty()) {
            geminiBatchService.createContentBatch(preparations.imagePrep.pendingRequests.map { it.request }).also {
                logger.info("[{}] Submitted image classification batch: {}", jobId, it)
            }
        } else null

        return SubmittedBatches(visualId, iconId, imageClassId)
    }

    // ==================== Phase 5: Process Media Results ====================

    private suspend fun processMediaResults(
        batches: SubmittedBatches,
        preparations: BatchPreparations,
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        jobId: Long
    ): MediaResults {
        val iconInterpretations = preparations.iconPrep.cachedResults.toMutableMap()
        val imageTexts = preparations.imagePrep.cachedResults.toMutableMap()

        // Process icon batch results
        val newIconResults = mutableMapOf<MediaHash, String?>()
        if (batches.iconId != null) {
            val iconResults = geminiBatchService.fetchBatchResults(batches.iconId)
            
            // Record token usage for icon batch
            val iconModelId = preparations.iconPrep.pendingRequests.firstOrNull()?.request?.modelId ?: "unknown"
            batchTokenUsageRecorder.recordBatchTokenUsage(jobId, "IconInterpretationBatch", iconModelId, iconResults)
            
            iconResults.forEachIndexed { index, result ->
                val pending = preparations.iconPrep.pendingRequests.getOrNull(index) ?: return@forEachIndexed
                if (result.success && result.generatedText != null) {
                    val label = parseIconResponse(result.generatedText!!, jobId)
                    iconInterpretations[pending.hash] = label
                    newIconResults[pending.hash] = label
                }
            }
            webpageIconInterpretationService.processBatchResults(newIconResults)
            logger.info("[{}] Processed {} icon interpretations ({} cached, {} new)",
                jobId, iconInterpretations.size, preparations.iconPrep.cachedResults.size, newIconResults.size)
        }

        // Process image classification batch results
        val newImageResults = mutableMapOf<MediaHash, String?>()
        val imagesWithTables = mutableMapOf<MediaHash, MediaData>()
        if (batches.imageClassId != null) {
            val imageResults = geminiBatchService.fetchBatchResults(batches.imageClassId)
            
            // Record token usage for image classification batch
            val imageModelId = preparations.imagePrep.pendingRequests.firstOrNull()?.request?.modelId ?: "unknown"
            batchTokenUsageRecorder.recordBatchTokenUsage(jobId, "ImageClassificationBatch", imageModelId, imageResults)
            
            imageResults.forEachIndexed { index, result ->
                val pending = preparations.imagePrep.pendingRequests.getOrNull(index) ?: return@forEachIndexed
                if (result.success && result.generatedText != null) {
                    val classification = parseImageClassificationResponse(result.generatedText!!, jobId)
                    if (classification?.needsTableInterpretation == true) {
                        preparations.imageDataMap[pending.hash]?.let { imagesWithTables[pending.hash] = it }
                    } else {
                        val text = classification?.text?.takeIf { it.isNotBlank() }
                        imageTexts[pending.hash] = text
                        newImageResults[pending.hash] = text
                    }
                }
            }
            logger.info("[{}] Processed image classifications: {} with text, {} need table extraction",
                jobId, imageTexts.size - preparations.imagePrep.cachedResults.size, imagesWithTables.size)
        }

        // Handle images containing tables
        if (imagesWithTables.isNotEmpty()) {
            processImageTableExtraction(imagesWithTables, imageTexts, newImageResults, preparations, job, eventFlow, jobId)
        }

        // Cache new image results
        if (newImageResults.isNotEmpty()) {
            webpageImageTextExtractionService.processBatchResults(newImageResults, preparations.imageDataMap)
        }

        return MediaResults(iconInterpretations, imageTexts)
    }

    private suspend fun processImageTableExtraction(
        imagesWithTables: Map<MediaHash, MediaData>,
        imageTexts: MutableMap<MediaHash, String?>,
        newImageResults: MutableMap<MediaHash, String?>,
        preparations: BatchPreparations,
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        jobId: Long
    ) {
        val tableExtractPrep = webpageImageTextExtractionService.prepareTableExtractionBatchRequests(imagesWithTables, jobId)
        
        if (tableExtractPrep.pendingRequests.isNotEmpty()) {
            val tableExtractBatchId = geminiBatchService.createContentBatch(tableExtractPrep.pendingRequests.map { it.request })
            logger.info("[{}] Submitted image table extraction batch: {}", jobId, tableExtractBatchId)
            
            pollBatchUntilComplete(job, eventFlow, tableExtractBatchId)
            
            val extractResults = geminiBatchService.fetchBatchResults(tableExtractBatchId)
            
            // Record token usage for image table extraction batch
            val tableExtractModelId = tableExtractPrep.pendingRequests.firstOrNull()?.request?.modelId ?: "unknown"
            batchTokenUsageRecorder.recordBatchTokenUsage(jobId, "ImageTableExtractionBatch", tableExtractModelId, extractResults)
            
            extractResults.forEachIndexed { index, result ->
                val pending = tableExtractPrep.pendingRequests.getOrNull(index) ?: return@forEachIndexed
                if (result.success && result.generatedText != null) {
                    val extraction = parseTableExtractionResponse(result.generatedText!!, jobId)
                    val text = extraction?.text?.takeIf { it.isNotBlank() }
                    imageTexts[pending.hash] = text
                    newImageResults[pending.hash] = text
                }
            }
            logger.info("[{}] Extracted tables from {} images", jobId, extractResults.count { it.success })
        }
    }

    // ==================== Phase 6: Process Page Results ====================

    private suspend fun processPageResults(
        batches: SubmittedBatches,
        preparations: BatchPreparations,
        urlStates: List<BatchUrlState>,
        collected: ContentCollectionResult,
        jobId: Long
    ) {
        // Process combined visual identification results (semantic + tables)
        if (batches.visualId != null) {
            val visualResults = geminiBatchService.fetchBatchResults(batches.visualId)
            
            // Record token usage for visual identification batch
            val visualModelId = preparations.visualPrep.pendingRequests.firstOrNull()?.request?.modelId ?: "unknown"
            batchTokenUsageRecorder.recordBatchTokenUsage(jobId, "VisualIdentificationBatch", visualModelId, visualResults)
            
            visualResults.forEachIndexed { index, result ->
                val pending = preparations.visualPrep.pendingRequests.getOrNull(index) ?: return@forEachIndexed
                val urlState = urlStates.find { it.id == pending.urlStateId.value } ?: return@forEachIndexed
                val basePath = urlState.snapshotBasePath ?: return@forEachIndexed
                val pageData = collected.urlPages[pending.urlStateId] ?: return@forEachIndexed
                
                try {
                    if (!result.success || result.generatedText == null) {
                        logger.warn("[{}] Visual ID batch failed for {}: {}", jobId, urlState.url, result.errorMessage)
                        return@forEachIndexed
                    }

                    // Get bounding boxes from collected data (already loaded from GCS)
                    val boundingBoxes = pageData.boundingBoxes
                    val pageWidth = boundingBoxes.values.maxOfOrNull { it.right } ?: 1920.0
                    val pageHeight = boundingBoxes.values.maxOfOrNull { it.bottom } ?: 1080.0
                    
                    val visualResult = visualIdentificationService.parseBatchResponse(
                        result.generatedText!!,
                        pending.htmlWithIds,
                        boundingBoxes,
                        pageWidth,
                        pageHeight
                    )

                    // Inject both semantic and table IDs into HTML
                    val doc = Jsoup.parse(pending.htmlWithIds)
                    
                    // Inject semantic element IDs
                    val semanticInjections = buildList {
                        visualResult.semanticElements.header?.let { add(it.cssSelector to it.dataId) }
                        visualResult.semanticElements.footer?.let { add(it.cssSelector to it.dataId) }
                        visualResult.semanticElements.navSidebar?.let { add(it.cssSelector to it.dataId) }
                        visualResult.semanticElements.breadcrumb?.let { add(it.cssSelector to it.dataId) }
                        visualResult.semanticElements.cookieBanner?.let { add(it.cssSelector to it.dataId) }
                        addAll(visualResult.semanticElements.adBanners.map { it.cssSelector to it.dataId })
                        addAll(visualResult.semanticElements.popups.map { it.cssSelector to it.dataId })
                    }
                    jsoupDomService.injectIdentifiers(doc, semanticInjections)
                    
                    // Inject table IDs
                    val tableInjections = visualResult.tables.map { it.cssSelector to it.dataId }
                    jsoupDomService.injectIdentifiers(doc, tableInjections)

                    // Store results to GCS
                    snapshotStorage.storeContentLlmResults(
                        basePath,
                        ContentLlmResults(
                            cleanedHtml = doc.html(),
                            semanticElements = visualResult.semanticElements,
                            tableIdentifications = visualResult.tables,
                            iconInterpretations = null,
                            imageTexts = null
                        )
                    )
                    
                    logger.debug("[{}] Processed visual ID for {}: {} semantic, {} tables",
                        jobId, urlState.url, 
                        countSemanticElements(visualResult.semanticElements),
                        visualResult.tables.size)
                } catch (e: Exception) {
                    logger.warn("[{}] Failed to process visual ID result for {}: {}", jobId, urlState.url, e.message)
                }
            }
        }
    }
    
    private fun countSemanticElements(elements: io.deepsearch.domain.models.valueobjects.SemanticElements): Int {
        var count = 0
        if (elements.header != null) count++
        if (elements.footer != null) count++
        if (elements.navSidebar != null) count++
        if (elements.breadcrumb != null) count++
        if (elements.cookieBanner != null) count++
        count += elements.adBanners.size
        count += elements.popups.size
        return count
    }

    // ==================== Phase 7: Store Media Results ====================

    private suspend fun storeMediaResultsToUrls(
        urlStates: List<BatchUrlState>,
        collected: ContentCollectionResult,
        mediaResults: MediaResults
    ) {
        urlStates.forEach { urlState ->
            try {
                val basePath = urlState.snapshotBasePath ?: return@forEach
                val urlStateId = BatchUrlStateId(urlState.id!!)
                val pageData = collected.urlPages[urlStateId] ?: return@forEach

                // Build icon interpretations for this URL (convert MediaHash to String for storage)
                val urlIconInterpretations = pageData.iconHashes.mapNotNull { hash ->
                    mediaResults.iconInterpretations[hash]?.let { hash.value to it }
                }.toMap()

                // Build image texts for this URL (convert MediaHash to String for storage)
                val urlImageTexts = pageData.imageHashes.mapNotNull { hash ->
                    mediaResults.imageTexts[hash]?.let { hash.value to it }
                }.toMap()

                if (urlIconInterpretations.isNotEmpty() || urlImageTexts.isNotEmpty()) {
                    // Store media results to GCS (update existing content LLM results)
                    snapshotStorage.storeContentLlmResults(
                        basePath,
                        ContentLlmResults(
                            cleanedHtml = null,  // Don't overwrite existing
                            semanticElements = null,
                            tableIdentifications = null,
                            iconInterpretations = urlIconInterpretations.takeIf { it.isNotEmpty() },
                            imageTexts = urlImageTexts.takeIf { it.isNotEmpty() }
                        )
                    )
                }
            } catch (e: Exception) {
                logger.warn("Failed to store media results for {}: {}", urlState.url, e.message)
            }
        }
    }

    // ==================== Phase 8: Finalize ====================

    private suspend fun finalizeContentStage(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        jobId: Long
    ) {
        val urlsToMark = batchUrlStateRepository.findNeedingContentLlmProcessing(jobId)
        urlsToMark.forEach { urlState ->
            if (!urlState.isFailed()) {
                // Delete icons from GCS (no longer needed after Stage 2)
                urlState.snapshotBasePath?.let { basePath ->
                    try {
                        snapshotStorage.deleteIcons(basePath)
                    } catch (e: Exception) {
                        logger.warn("Failed to delete icons for {}: {}", urlState.url, e.message)
                    }
                }
                
                urlState.markContentLlmDone()
                batchUrlStateRepository.update(urlState)
            }
        }

        job.urlsContentProcessed = urlsToMark.count { !it.isFailed() }
        job.advanceToNextStage()
        batchJobRepository.update(job)
        eventEmitter.emit(job, eventFlow, "Stage 2 complete: ${job.urlsContentProcessed} pages analyzed")
    }

    // ==================== Polling Helpers ====================

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
                    eventEmitter.emit(job, eventFlow, "Waiting for batches (${completedBatches.size}/${batchIds.size} complete)")
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
                    BatchJobState.FAILED -> throw RuntimeException("Batch job failed: ${status.errorMessage}")
                    BatchJobState.CANCELLED -> throw RuntimeException("Batch job was cancelled")
                    else -> {
                        eventEmitter.emit(job, eventFlow, "Waiting for batch (${status.completedRequests}/${status.totalRequests})")
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

    // ==================== Response Parsing Helpers ====================

    private fun parseIconResponse(responseText: String, jobId: Long): String? {
        return try {
            val parsed = json.decodeFromString<IconBatchResponseWrapper>(responseText)
            parsed.icons.firstOrNull()?.label
        } catch (e: Exception) {
            logger.warn("[{}] Failed to parse icon response: {}", jobId, e.message)
            null
        }
    }

    private fun parseImageClassificationResponse(responseText: String, jobId: Long): ImageClassificationResponseWrapper? {
        return try {
            json.decodeFromString<ImageClassificationResponseWrapper>(responseText)
        } catch (e: Exception) {
            logger.warn("[{}] Failed to parse image classification: {}", jobId, e.message)
            null
        }
    }

    private fun parseTableExtractionResponse(responseText: String, jobId: Long): TableExtractionResponseWrapper? {
        return try {
            json.decodeFromString<TableExtractionResponseWrapper>(responseText)
        } catch (e: Exception) {
            logger.warn("[{}] Failed to parse table extraction: {}", jobId, e.message)
            null
        }
    }
}

