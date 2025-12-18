package io.deepsearch.application.services.batch

import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Interface for batch periodic index stage handlers.
 * Each stage of the batch pipeline is implemented as a separate handler.
 */
interface IBatchStageHandler {
    /**
     * Execute this stage of the batch pipeline.
     * 
     * @param job The batch job being processed
     * @param eventFlow Flow to emit progress events
     */
    suspend fun execute(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    )
}

