package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IWebpageIndexingService
import io.deepsearch.application.services.QuickCaptureData
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.IBatchSnapshotStorageService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Lightweight replacement for Stages 2+3 of the batch pipeline.
 *
 * Instead of multi-stage Gemini Batch API calls for content LLM and table
 * interpretation, this handler reads the captured data from GCS, reconstructs
 * [QuickCaptureData], and delegates to [IWebpageIndexingService.indexFromCapturedData]
 * which uses real-time LLM calls.
 *
 * Trade-off: simpler pipeline and same extraction quality as the interactive
 * periodic index, but without the Gemini Batch API's 50% cost savings.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class LightweightIndexHandler(
    private val batchJobRepository: IBatchPeriodicIndexJobRepository,
    private val batchUrlStateRepository: IBatchUrlStateRepository,
    private val snapshotStorage: IBatchSnapshotStorageService,
    private val webpageIndexingService: IWebpageIndexingService,
    private val eventEmitter: BatchEventEmitter
) : IBatchStageHandler {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun execute(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Lightweight index: processing extracted URLs with real-time LLM", jobId)
        eventEmitter.emit(job, eventFlow, "Lightweight index: processing with real-time LLM...")

        val extractedUrls = batchUrlStateRepository.findNeedingContentLlmProcessing(jobId)
        logger.info("[{}] {} URLs to process", jobId, extractedUrls.size)

        if (extractedUrls.isEmpty()) {
            skipToEmbeddingStage(job, eventFlow)
            return
        }

        val sessionId = PeriodicIndexSessionId(jobId)
        val semaphore = Semaphore(CONCURRENCY)
        var successCount = 0

        coroutineScope {
            extractedUrls.map { urlState ->
                async {
                    semaphore.withPermit {
                        processUrl(urlState, sessionId, jobId)
                    }
                }
            }.awaitAll().forEach { success -> if (success) successCount++ }
        }

        logger.info("[{}] Lightweight index complete: {}/{} URLs", jobId, successCount, extractedUrls.size)

        skipToEmbeddingStage(job, eventFlow)
        eventEmitter.emit(
            job, eventFlow,
            "Lightweight index complete: $successCount/${extractedUrls.size} URLs processed"
        )
    }

    private suspend fun processUrl(
        urlState: BatchUrlState,
        sessionId: PeriodicIndexSessionId,
        jobId: Long
    ): Boolean {
        val basePath = urlState.snapshotBasePath ?: return false

        return try {
            val capturedData = reconstructCapturedData(urlState, basePath)
                ?: run {
                    logger.warn("[{}] Could not reconstruct capture data for {}", jobId, urlState.url)
                    urlState.markFailed("Missing snapshot HTML or screenshot in GCS")
                    batchUrlStateRepository.update(urlState)
                    return false
                }

            val result = webpageIndexingService.indexFromCapturedData(capturedData, sessionId)
            snapshotStorage.storeLightweightMarkdown(basePath, result.markdown)

            // Clean up icons and images (no longer needed after indexing)
            snapshotStorage.deleteIcons(basePath)
            snapshotStorage.deleteImages(basePath)

            urlState.markFinalLlmDone()
            batchUrlStateRepository.update(urlState)
            true
        } catch (e: Exception) {
            logger.warn("[{}] Lightweight index failed for {}: {}", jobId, urlState.url, e.message)
            urlState.markFailed(e.message ?: "Lightweight indexing failed")
            batchUrlStateRepository.update(urlState)
            false
        }
    }

    private suspend fun reconstructCapturedData(
        urlState: BatchUrlState,
        basePath: String
    ): QuickCaptureData? {
        val snapshotHtml = snapshotStorage.readSnapshotHtml(basePath) ?: return null
        val boundingBoxes = snapshotStorage.readBoundingBoxes(basePath) ?: emptyMap()
        val screenshotData = snapshotStorage.readScreenshot(basePath) ?: return null

        val snapshot = IBrowserPage.PageSnapshotWithMetadata(
            title = urlState.title ?: "",
            description = urlState.description,
            url = urlState.url,
            html = snapshotHtml,
            boundingBoxes = boundingBoxes
        )

        val screenshot = IBrowserPage.Screenshot(
            bytes = screenshotData.bytes,
            mimeType = ImageMimeType.fromValue(screenshotData.mimeType)
        )

        return QuickCaptureData(snapshot, screenshot)
    }

    private suspend fun skipToEmbeddingStage(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        job.state = BatchPeriodicIndexJobState.PARALLEL_EMBEDDING_AND_KG_EXTRACTION
        job.updatedAt = kotlin.time.Clock.System.now()
        batchJobRepository.update(job)
    }

    companion object {
        private const val CONCURRENCY = 5
    }
}
