package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.UserId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * State machine for batch periodic index jobs.
 * Batch jobs go through 4 stages with async Gemini batch processing.
 */
enum class BatchPeriodicIndexJobState {
    /** Stage 1: Combined crawling + browser extraction (single browser visit per URL) */
    CRAWL_AND_EXTRACT,
    /** Stage 2: Waiting for Gemini batch job (semantic identification, table identification, icons/images) */
    BATCHING_CONTENT_LLM,
    /** Stage 3: Waiting for Gemini batch job (table interpretation) */
    BATCHING_FINAL_LLM,
    /** Stage 4: Writing cache and embeddings to DB */
    WRITING_CACHE,
    /** Successfully completed all stages */
    COMPLETED,
    /** Failed due to an error */
    FAILED,
    /** Stopped by user */
    STOPPED
}

/**
 * Persistent record of a batch periodic index job.
 * 
 * Batch jobs use Gemini's Batch API for cost-effective background processing (50% cost savings).
 * Unlike interactive periodic index jobs, batch jobs can take up to 24+ hours to complete.
 * 
 * The job progresses through 4 stages:
 * 1. CRAWL_AND_EXTRACT - Combined link discovery + browser extraction (single visit per URL)
 * 2. BATCHING_CONTENT_LLM - Async batch LLM for semantic/table/icon identification
 * 3. BATCHING_FINAL_LLM - Async batch LLM for table interpretation
 * 4. WRITING_CACHE - Write markdown/embeddings to cache
 * 
 * State is persisted at each stage for resumption after server restarts.
 */
@OptIn(ExperimentalTime::class)
class BatchPeriodicIndexJob(
    var id: Long? = null,
    val userId: UserId,
    val baseUrl: String,
    val maxUrlCount: Int,
    val sitemapUrl: String? = null,
    val createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now(),
    var state: BatchPeriodicIndexJobState = BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT,
    var version: Long = 0,
    /**
     * Language filter pattern for URL filtering during crawling.
     * Can be either:
     * - Path pattern: `/en-us/` - matches URLs with this path segment
     * - Query pattern: `?lang=en` - matches URLs with this query parameter
     * Null means no language filtering (crawl all languages).
     */
    val languagePattern: String? = null,
    /**
     * OCR language for Tesseract text extraction from images.
     * Defaults to English.
     */
    val ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT,
    /**
     * Current Gemini batch job ID when in BATCHING_CONTENT_LLM or BATCHING_FINAL_LLM state.
     * Used to poll for batch completion and resume after server restart.
     */
    var geminiBatchJobId: String? = null,
    /**
     * Timestamp when the current Gemini batch job was created.
     * Used for timeout detection and progress estimation.
     */
    var batchJobCreatedAt: Instant? = null,
    /**
     * Timestamp when the job was last resumed (after server restart or pause).
     */
    var lastResumedAt: Instant? = null,
    /**
     * Error message if the job failed.
     */
    var errorMessage: String? = null,
    /**
     * Number of URLs successfully processed in Stage 1 (crawl + extract combined).
     * Each URL is visited once with browser, extracting HTML/icons/images and discovering links.
     */
    var urlsProcessed: Int = 0,
    /**
     * Number of URLs with content LLM processing complete (Stage 2).
     */
    var urlsContentProcessed: Int = 0,
    /**
     * Number of URLs with final LLM processing complete (Stage 3).
     */
    var urlsFinalProcessed: Int = 0,
    /**
     * Number of URLs successfully cached (Stage 4).
     */
    var urlsCached: Int = 0
) {
    /**
     * Get the current stage number (1-4).
     */
    fun currentStage(): Int = when (state) {
        BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT -> 1
        BatchPeriodicIndexJobState.BATCHING_CONTENT_LLM -> 2
        BatchPeriodicIndexJobState.BATCHING_FINAL_LLM -> 3
        BatchPeriodicIndexJobState.WRITING_CACHE -> 4
        BatchPeriodicIndexJobState.COMPLETED -> 4
        BatchPeriodicIndexJobState.FAILED, BatchPeriodicIndexJobState.STOPPED -> currentStageFromProgress()
    }

    private fun currentStageFromProgress(): Int = when {
        urlsCached > 0 -> 4
        urlsFinalProcessed > 0 -> 3
        urlsContentProcessed > 0 -> 2
        else -> 1
    }

    /**
     * Get a human-readable description of the current stage.
     */
    fun stageDescription(): String = when (state) {
        BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT -> "Crawling & extracting webpages"
        BatchPeriodicIndexJobState.BATCHING_CONTENT_LLM -> "Processing with Gemini (content analysis)"
        BatchPeriodicIndexJobState.BATCHING_FINAL_LLM -> "Processing with Gemini (table interpretation)"
        BatchPeriodicIndexJobState.WRITING_CACHE -> "Saving results"
        BatchPeriodicIndexJobState.COMPLETED -> "Completed"
        BatchPeriodicIndexJobState.FAILED -> "Failed: ${errorMessage ?: "Unknown error"}"
        BatchPeriodicIndexJobState.STOPPED -> "Stopped"
    }

    /**
     * Transition to the next stage after current stage completes successfully.
     */
    fun advanceToNextStage() {
        state = when (state) {
            BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT -> BatchPeriodicIndexJobState.BATCHING_CONTENT_LLM
            BatchPeriodicIndexJobState.BATCHING_CONTENT_LLM -> BatchPeriodicIndexJobState.BATCHING_FINAL_LLM
            BatchPeriodicIndexJobState.BATCHING_FINAL_LLM -> BatchPeriodicIndexJobState.WRITING_CACHE
            BatchPeriodicIndexJobState.WRITING_CACHE -> BatchPeriodicIndexJobState.COMPLETED
            else -> state // No transition for terminal states
        }
        updatedAt = Clock.System.now()
    }

    /**
     * Mark the job as failed with an error message.
     */
    fun markFailed(message: String) {
        state = BatchPeriodicIndexJobState.FAILED
        errorMessage = message
        updatedAt = Clock.System.now()
    }

    /**
     * Mark the job as stopped by user.
     */
    fun markStopped() {
        if (state != BatchPeriodicIndexJobState.COMPLETED && state != BatchPeriodicIndexJobState.FAILED) {
            state = BatchPeriodicIndexJobState.STOPPED
            updatedAt = Clock.System.now()
        }
    }

    /**
     * Mark the job as completed.
     */
    fun markCompleted() {
        state = BatchPeriodicIndexJobState.COMPLETED
        updatedAt = Clock.System.now()
    }

    /**
     * Set the Gemini batch job ID when submitting a batch.
     */
    fun setBatchJob(batchJobId: String) {
        geminiBatchJobId = batchJobId
        batchJobCreatedAt = Clock.System.now()
        updatedAt = Clock.System.now()
    }

    /**
     * Clear the Gemini batch job ID after batch completes.
     */
    fun clearBatchJob() {
        geminiBatchJobId = null
        batchJobCreatedAt = null
        updatedAt = Clock.System.now()
    }

    /**
     * Mark the job as resumed (after server restart).
     */
    fun markResumed() {
        lastResumedAt = Clock.System.now()
        updatedAt = Clock.System.now()
    }

    /**
     * Check if the job is in a terminal state.
     */
    fun isTerminal(): Boolean = state in listOf(
        BatchPeriodicIndexJobState.COMPLETED,
        BatchPeriodicIndexJobState.FAILED,
        BatchPeriodicIndexJobState.STOPPED
    )

    /**
     * Check if the job is waiting for a Gemini batch to complete.
     */
    fun isWaitingForBatch(): Boolean = state in listOf(
        BatchPeriodicIndexJobState.BATCHING_CONTENT_LLM,
        BatchPeriodicIndexJobState.BATCHING_FINAL_LLM
    ) && geminiBatchJobId != null
}
