package io.deepsearch.application.services.batch

import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.BatchUrlStateId
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.ContentLlmResults
import io.deepsearch.domain.services.IBatchSnapshotStorageService
import io.deepsearch.domain.services.IGeminiBatchService
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Stage 2: Content LLM batch handler.
 *
 * Orchestrates parallel batch jobs for:
 * - Visual identification (semantic elements + tables in single call)
 * - Icon interpretation
 * - Image classification (with follow-up table extraction for images containing tables)
 *
 * Icons and images are deduplicated by hash across all URLs.
 *
 * Processing is split into dedicated phase classes:
 * - ContentDataCollector: Collects data from GCS
 * - ContentBatchPreparer: Prepares batch requests
 * - MediaResultProcessor: Processes icon/image results
 * - PageResultProcessor: Processes visual identification results
 */
class ContentLlmBatchHandler(
    private val batchJobRepository: IBatchPeriodicIndexJobRepository,
    private val batchUrlStateRepository: IBatchUrlStateRepository,
    private val geminiBatchService: IGeminiBatchService,
    private val snapshotStorage: IBatchSnapshotStorageService,
    private val eventEmitter: BatchEventEmitter,
    // Phase processors
    private val dataCollector: ContentDataCollector,
    private val batchPreparer: ContentBatchPreparer,
    private val mediaResultProcessor: MediaResultProcessor,
    private val pageResultProcessor: PageResultProcessor,
    private val pollingService: BatchPollingService
) : IBatchStageHandler {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

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

        // Phase 1: Collect data from GCS
        val collected = dataCollector.collect(urlStates)
        if (collected.isEmpty()) {
            logger.info("[{}] No batch requests to submit, advancing", jobId)
            job.advanceToNextStage()
            batchJobRepository.update(job)
            return
        }

        // Phase 2: Prepare batch requests
        val preparations = batchPreparer.prepare(collected, jobId)

        // Phase 3: Apply cached results immediately
        pageResultProcessor.applyCachedResults(preparations, urlStates, collected)

        // Phase 4: Submit batches
        val batches = submitBatches(preparations, jobId)
        eventEmitter.emit(job, eventFlow, "Submitted ${batches.count()} parallel batches")

        // Poll until all batches complete
        pollingService.pollMultipleUntilComplete(job, eventFlow, batches.allIds())

        // Phase 5: Process media results (icons + images)
        val mediaResults = mediaResultProcessor.process(batches, preparations, job, eventFlow, jobId)

        // Phase 6: Process page results (semantic + tables)
        pageResultProcessor.process(batches, preparations, urlStates, collected, jobId)

        // Phase 7: Store media interpretations back to URLs
        storeMediaResultsToUrls(urlStates, collected, mediaResults)

        // Phase 8: Finalize stage
        finalizeContentStage(job, eventFlow, jobId)
    }

    // ==================== Phase 4: Submit Batches ====================

    private suspend fun submitBatches(
        preparations: ContentBatchPreparer.BatchPreparations,
        jobId: Long
    ): SubmittedBatches {
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
}
