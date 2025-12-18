package io.deepsearch.application.services.batch

import io.deepsearch.application.services.ISemanticIdentificationService
import io.deepsearch.application.services.ITableIdentificationService
import io.deepsearch.application.services.IWebpageIconInterpretationService
import io.deepsearch.application.services.IWebpageImageTextExtractionService
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchUrlSnapshotData
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.BatchUrlStateId
import io.deepsearch.domain.models.valueobjects.MediaHash
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.BatchJobState
import io.deepsearch.domain.services.IGeminiBatchService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Stage 2: Content LLM batch handler.
 * 
 * Submits parallel batch jobs for:
 * - Semantic identification
 * - Table identification  
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
    private val semanticIdentificationService: ISemanticIdentificationService,
    private val tableIdentificationService: ITableIdentificationService,
    private val webpageIconInterpretationService: IWebpageIconInterpretationService,
    private val webpageImageTextExtractionService: IWebpageImageTextExtractionService,
    private val eventEmitter: BatchEventEmitter
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
        applyCachedResults(preparations, urlStates)

        // 4. Submit and poll batches
        val batches = submitBatches(preparations, jobId)
        eventEmitter.emit(job, eventFlow, "Submitted ${batches.count()} parallel batches")
        pollMultipleBatchesUntilComplete(job, eventFlow, batches.allIds())

        // 5. Process media results (icons + images)
        val mediaResults = processMediaResults(batches, preparations, job, eventFlow, jobId)

        // 6. Process page results (semantic + tables)
        processPageResults(batches, preparations, urlStates, jobId)

        // 7. Store media interpretations back to URLs
        storeMediaResultsToUrls(urlStates, collected, mediaResults)

        // 8. Finalize stage
        finalizeContentStage(job, eventFlow, jobId)
    }

    // ==================== Phase 1: Collect Data ====================

    private fun collectContentData(urlStates: List<BatchUrlState>): ContentCollectionResult {
        val urlPages = mutableMapOf<BatchUrlStateId, UrlPageData>()
        val uniqueIcons = mutableMapOf<MediaHash, io.deepsearch.domain.models.entities.BatchIconData>()
        val uniqueImages = mutableMapOf<MediaHash, io.deepsearch.domain.models.entities.BatchImageData>()

        urlStates.forEach { urlState ->
            try {
                val snapshotData = urlState.snapshotData?.let { 
                    json.decodeFromString<BatchUrlSnapshotData>(it) 
                } ?: return@forEach

                val urlStateId = BatchUrlStateId(urlState.id!!)

                // Collect icon hashes
                val iconHashes = mutableListOf<MediaHash>()
                snapshotData.icons?.forEach { icon ->
                    val hash = MediaHash(icon.hashBase64)
                    uniqueIcons.putIfAbsent(hash, icon)
                    iconHashes.add(hash)
                }

                // Collect image hashes
                val imageHashes = mutableListOf<MediaHash>()
                snapshotData.images?.forEach { image ->
                    val hash = MediaHash(image.hashBase64)
                    uniqueImages.putIfAbsent(hash, image)
                    imageHashes.add(hash)
                }

                urlPages[urlStateId] = UrlPageData(
                    urlStateId = urlStateId,
                    html = snapshotData.html,
                    boundingBoxes = snapshotData.boundingBoxes ?: emptyMap(),
                    iconHashes = iconHashes,
                    imageHashes = imageHashes
                )
            } catch (e: Exception) {
                logger.warn("Failed to collect data for {}: {}", urlState.url, e.message)
            }
        }

        return ContentCollectionResult(urlPages, uniqueIcons, uniqueImages)
    }

    // ==================== Phase 2: Prepare Batches ====================

    private data class BatchPreparations(
        val semanticPrep: io.deepsearch.application.services.SemanticBatchPreparation,
        val tablePrep: io.deepsearch.application.services.TableIdentificationBatchPreparation,
        val iconPrep: io.deepsearch.application.services.IconBatchPreparation,
        val imagePrep: io.deepsearch.application.services.ImageBatchPreparation,
        val iconDataMap: Map<MediaHash, MediaData>,
        val imageDataMap: Map<MediaHash, MediaData>
    )

    private suspend fun prepareBatches(collected: ContentCollectionResult, jobId: Long): BatchPreparations {
        val pagesForServices = collected.pagesForBatchServices()

        // Prepare semantic identification batch
        val semanticPrep = semanticIdentificationService.prepareBatchRequests(pagesForServices, jobId)

        // Prepare table identification batch
        val tablePrep = tableIdentificationService.prepareBatchRequests(pagesForServices, jobId)

        // Prepare icon interpretation batch
        val iconDataMap = collected.uniqueIcons.mapValues { (_, iconData) ->
            val iconBytes = kotlin.io.encoding.Base64.decode(iconData.bytesBase64)
            MediaData(iconBytes, iconData.mimeType)
        }
        val iconPrep = webpageIconInterpretationService.prepareBatchRequests(iconDataMap, jobId)

        // Prepare image classification batch
        val imageDataMap = collected.uniqueImages.mapValues { (_, imageData) ->
            val imageBytes = kotlin.io.encoding.Base64.decode(imageData.bytesBase64)
            MediaData(imageBytes, imageData.mimeType)
        }
        val imagePrep = webpageImageTextExtractionService.prepareBatchRequests(imageDataMap, jobId)

        logger.info("[{}] Prepared batches: {} semantic, {} table, {} icons, {} images",
            jobId, semanticPrep.pendingRequests.size, tablePrep.pendingRequests.size,
            iconPrep.pendingRequests.size, imagePrep.pendingRequests.size)

        return BatchPreparations(semanticPrep, tablePrep, iconPrep, imagePrep, iconDataMap, imageDataMap)
    }

    // ==================== Phase 3: Apply Cached Results ====================

    private suspend fun applyCachedResults(preparations: BatchPreparations, urlStates: List<BatchUrlState>) {
        // Apply cached semantic results
        // Note: For cached results, we use the existing HTML since we didn't call prepareBatchRequest
        for ((urlStateId, semanticElements) in preparations.semanticPrep.cachedResults) {
            val urlState = urlStates.find { it.id == urlStateId.value } ?: continue
            val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) } ?: continue
            val updatedSnapshot = snapshotData.copy(
                cleanedHtml = snapshotData.html,
                semanticElements = semanticElements
            )
            urlState.snapshotData = json.encodeToString(updatedSnapshot)
            batchUrlStateRepository.update(urlState)
        }

        // Apply cached table results
        for ((urlStateId, tableIdentifications) in preparations.tablePrep.cachedResults) {
            val urlState = urlStates.find { it.id == urlStateId.value } ?: continue
            val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) } ?: continue
            val updatedSnapshot = snapshotData.copy(tableIdentifications = tableIdentifications)
            urlState.snapshotData = json.encodeToString(updatedSnapshot)
            batchUrlStateRepository.update(urlState)
        }
    }

    // ==================== Phase 4: Submit Batches ====================

    private suspend fun submitBatches(preparations: BatchPreparations, jobId: Long): SubmittedBatches {
        val semanticId = if (preparations.semanticPrep.pendingRequests.isNotEmpty()) {
            geminiBatchService.createContentBatch(preparations.semanticPrep.pendingRequests.map { it.request }).also {
                logger.info("[{}] Submitted semantic batch: {}", jobId, it)
            }
        } else null

        val tableId = if (preparations.tablePrep.pendingRequests.isNotEmpty()) {
            geminiBatchService.createContentBatch(preparations.tablePrep.pendingRequests.map { it.request }).also {
                logger.info("[{}] Submitted table ID batch: {}", jobId, it)
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

        return SubmittedBatches(semanticId, tableId, iconId, imageClassId)
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
            imageResults.forEachIndexed { index, result ->
                val pending = preparations.imagePrep.pendingRequests.getOrNull(index) ?: return@forEachIndexed
                if (result.success && result.generatedText != null) {
                    val classification = parseImageClassificationResponse(result.generatedText!!, jobId)
                    if (classification?.containsTable == true) {
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
        jobId: Long
    ) {
        // Process semantic results
        if (batches.semanticId != null) {
            val semanticResults = geminiBatchService.fetchBatchResults(batches.semanticId)
            semanticResults.forEachIndexed { index, result ->
                val pending = preparations.semanticPrep.pendingRequests.getOrNull(index) ?: return@forEachIndexed
                val urlState = urlStates.find { it.id == pending.urlStateId.value } ?: return@forEachIndexed
                
                try {
                    if (!result.success || result.generatedText == null) {
                        logger.warn("[{}] Semantic batch failed for {}: {}", jobId, urlState.url, result.errorMessage)
                        return@forEachIndexed
                    }

                    val semanticElements = semanticIdentificationService.parseBatchResponse(result.generatedText!!, pending.htmlWithIds)

                    // Cache the result
                    val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) } ?: return@forEachIndexed
                    val htmlHash = MessageDigest.getInstance("SHA-256").digest(snapshotData.html.toByteArray())
                    semanticIdentificationService.cacheResult(htmlHash, semanticElements)

                    val updatedSnapshot = snapshotData.copy(cleanedHtml = pending.htmlWithIds, semanticElements = semanticElements)
                    urlState.snapshotData = json.encodeToString(updatedSnapshot)
                    batchUrlStateRepository.update(urlState)
                } catch (e: Exception) {
                    logger.warn("[{}] Failed to process semantic result for {}: {}", jobId, urlState.url, e.message)
                }
            }
        }

        // Process table identification results
        if (batches.tableId != null) {
            val tableResults = geminiBatchService.fetchBatchResults(batches.tableId)
            tableResults.forEachIndexed { index, result ->
                val pending = preparations.tablePrep.pendingRequests.getOrNull(index) ?: return@forEachIndexed
                val urlState = urlStates.find { it.id == pending.urlStateId.value } ?: return@forEachIndexed
                
                try {
                    if (!result.success || result.generatedText == null) {
                        logger.warn("[{}] Table ID batch failed for {}: {}", jobId, urlState.url, result.errorMessage)
                        return@forEachIndexed
                    }

                    val tableIdentifications = tableIdentificationService.parseBatchResponse(result.generatedText!!, pending.htmlWithIds)

                    // Cache the result
                    val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) } ?: return@forEachIndexed
                    val htmlHash = MessageDigest.getInstance("SHA-256").digest(snapshotData.html.toByteArray())
                    tableIdentificationService.cacheResult(htmlHash, tableIdentifications)

                    val updatedSnapshot = snapshotData.copy(tableIdentifications = tableIdentifications)
                    urlState.snapshotData = json.encodeToString(updatedSnapshot)
                    batchUrlStateRepository.update(urlState)
                } catch (e: Exception) {
                    logger.warn("[{}] Failed to process table ID result for {}: {}", jobId, urlState.url, e.message)
                }
            }
        }
    }

    // ==================== Phase 7: Store Media Results ====================

    private suspend fun storeMediaResultsToUrls(
        urlStates: List<BatchUrlState>,
        collected: ContentCollectionResult,
        mediaResults: MediaResults
    ) {
        urlStates.forEach { urlState ->
            try {
                val snapshotData = urlState.snapshotData?.let {
                    json.decodeFromString<BatchUrlSnapshotData>(it)
                } ?: return@forEach

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
                    val updatedSnapshot = snapshotData.copy(
                        iconInterpretations = urlIconInterpretations.takeIf { it.isNotEmpty() },
                        imageTexts = urlImageTexts.takeIf { it.isNotEmpty() }
                    )
                    urlState.snapshotData = json.encodeToString(updatedSnapshot)
                    batchUrlStateRepository.update(urlState)
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
                urlState.markContentLlmDone(urlState.snapshotData ?: "")
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

