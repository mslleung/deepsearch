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
            BatchUrlStageCounts(0, 0, 0, 0, 0, 0, 0)
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

