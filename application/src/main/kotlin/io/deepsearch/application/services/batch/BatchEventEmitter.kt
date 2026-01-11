package io.deepsearch.application.services.batch

import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.repositories.BatchUrlStageCounts
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Helper class for emitting batch progress events.
 * Shared across all stage handlers.
 */
class BatchEventEmitter(
    private val batchUrlStateRepository: IBatchUrlStateRepository
) {
    suspend fun emit(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        message: String
    ) {
        val counts = try {
            batchUrlStateRepository.countByStage(requireNotNull(job.id))
        } catch (e: Exception) {
            BatchUrlStageCounts(
                total = 0,
                pending = 0,
                extracted = 0,
                contentLlmDone = 0,
                finalLlmDone = 0,
                pendingFileUpload = 0,
                fileUploaded = 0,
                cached = 0,
                failed = 0
            )
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
                filesPendingUpload = counts.pendingFileUpload,
                filesUploaded = counts.fileUploaded,
                batchJobIds = job.batchJobIds,
                errorMessage = job.errorMessage,
                message = message
            )
        )
    }
}

