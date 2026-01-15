package io.deepsearch.application.services.batch

import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.services.BatchJobState
import io.deepsearch.domain.services.IGeminiBatchService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Shared service for polling Gemini batch jobs until completion.
 * Extracts duplicated polling logic from stage handlers.
 */
class BatchPollingService(
    private val geminiBatchService: IGeminiBatchService,
    private val eventEmitter: BatchEventEmitter
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        const val POLL_INTERVAL_MS = 60_000L
        const val MAX_POLL_ATTEMPTS = 1440
    }

    /**
     * Poll a single batch job until it completes or fails.
     *
     * @param job The batch periodic index job (for event emission)
     * @param eventFlow Flow to emit progress events
     * @param batchId The Gemini batch job ID to poll
     * @param description Human-readable description for logging/events (e.g., "embedding", "KG extraction")
     */
    suspend fun pollUntilComplete(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        batchId: String,
        description: String = "batch"
    ) {
        var attempts = 0

        while (attempts < MAX_POLL_ATTEMPTS) {
            try {
                val status = geminiBatchService.pollBatchStatus(batchId)

                when (status.state) {
                    BatchJobState.SUCCEEDED -> {
                        logger.info("{} job {} completed successfully", description.replaceFirstChar { it.uppercase() }, batchId)
                        return
                    }
                    BatchJobState.FAILED -> {
                        throw RuntimeException("$description batch job failed: ${status.errorMessage}")
                    }
                    BatchJobState.CANCELLED -> {
                        throw RuntimeException("$description batch job was cancelled")
                    }
                    else -> {
                        eventEmitter.emit(
                            job,
                            eventFlow,
                            "Waiting for $description batch (${status.completedRequests}/${status.totalRequests})"
                        )
                    }
                }
            } catch (e: Exception) {
                if (e.message?.contains("429") == true) {
                    logger.warn("Rate limited while polling {}, will retry", description)
                } else {
                    throw e
                }
            }

            delay(POLL_INTERVAL_MS)
            attempts++
        }

        throw RuntimeException("$description batch job polling timed out after $MAX_POLL_ATTEMPTS attempts")
    }

    /**
     * Poll multiple batch jobs in sequence until all complete.
     * Useful when multiple batches are submitted in parallel but need sequential polling.
     *
     * @param job The batch periodic index job (for event emission)
     * @param eventFlow Flow to emit progress events
     * @param batchIds List of Gemini batch job IDs to poll
     */
    suspend fun pollMultipleUntilComplete(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        batchIds: List<String>
    ) {
        if (batchIds.isEmpty()) return

        val completedBatches = mutableSetOf<String>()
        var attempts = 0

        while (completedBatches.size < batchIds.size && attempts < MAX_POLL_ATTEMPTS) {
            try {
                batchIds.forEach { batchId ->
                    if (batchId in completedBatches) return@forEach

                    val status = geminiBatchService.pollBatchStatus(batchId)
                    when (status.state) {
                        BatchJobState.SUCCEEDED -> {
                            logger.info("Batch {} completed", batchId)
                            completedBatches.add(batchId)
                        }
                        BatchJobState.FAILED -> {
                            throw RuntimeException("Batch $batchId failed: ${status.errorMessage}")
                        }
                        BatchJobState.CANCELLED -> {
                            throw RuntimeException("Batch $batchId was cancelled")
                        }
                        else -> {}
                    }
                }

                if (completedBatches.size < batchIds.size) {
                    eventEmitter.emit(
                        job,
                        eventFlow,
                        "Waiting for batches (${completedBatches.size}/${batchIds.size} complete)"
                    )
                    delay(POLL_INTERVAL_MS)
                }
            } catch (e: Exception) {
                if (e.message?.contains("429") == true) {
                    logger.warn("Rate limited while polling, will retry")
                    delay(POLL_INTERVAL_MS)
                } else {
                    throw e
                }
            }
            attempts++
        }

        if (completedBatches.size < batchIds.size) {
            throw RuntimeException("Batch polling timed out after $MAX_POLL_ATTEMPTS attempts")
        }
    }
}
