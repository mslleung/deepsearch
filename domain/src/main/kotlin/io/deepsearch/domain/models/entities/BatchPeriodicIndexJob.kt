package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.UserId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * State machine for batch periodic index jobs.
 * Batch jobs go through 5 stages with async Gemini batch processing.
 */
enum class BatchPeriodicIndexJobState {
    /** Stage 1: Browser-based crawling + extraction (single browser visit per URL) */
    CRAWL_AND_EXTRACT,
    /** Stage 2: LLM batch for semantic identification, table identification, icons/images */
    CONTENT_LLM_BATCH,
    /** Stage 3: LLM batch for table interpretation */
    LLM_TABLE_INTERPRETATION,
    /** Stage 4: Parallel page embedding batch + KG extraction batch */
    PARALLEL_EMBEDDING_AND_KG_EXTRACTION,
    /** Stage 5: Batch embedding for KG entities */
    KG_ENTITY_EMBEDDINGS,
    /** Stage 6: Waiting for background file uploads to complete */
    PROCESSING_FILES,
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
 * The job progresses through 5 stages:
 * 1. CRAWL_AND_EXTRACT - Browser-based crawl + extraction (single visit per URL)
 * 2. CONTENT_LLM_BATCH - LLM batch for semantic/table/icon identification
 * 3. LLM_TABLE_INTERPRETATION - LLM batch for table interpretation
 * 4. PARALLEL_EMBEDDING_AND_KG_EXTRACTION - Page embedding batch + KG extraction batch (run in parallel)
 * 5. KG_ENTITY_EMBEDDINGS - Batch embedding for extracted KG entities
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
     * Active Gemini batch job IDs for the current stage.
     * Most stages have 1 batch job, Stage 4 has 2 (embedding + KG extraction).
     * Used to poll for batch completion and resume after server restart.
     */
    var batchJobIds: List<String> = emptyList(),
    /**
     * Timestamp when the current batch job(s) were created.
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
     * Get the current stage number (1-6).
     */
    fun currentStage(): Int = when (state) {
        BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT -> 1
        BatchPeriodicIndexJobState.CONTENT_LLM_BATCH -> 2
        BatchPeriodicIndexJobState.LLM_TABLE_INTERPRETATION -> 3
        BatchPeriodicIndexJobState.PARALLEL_EMBEDDING_AND_KG_EXTRACTION -> 4
        BatchPeriodicIndexJobState.KG_ENTITY_EMBEDDINGS -> 5
        BatchPeriodicIndexJobState.PROCESSING_FILES -> 6
        BatchPeriodicIndexJobState.COMPLETED -> 6
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
        BatchPeriodicIndexJobState.CONTENT_LLM_BATCH -> "Processing with Gemini (content analysis)"
        BatchPeriodicIndexJobState.LLM_TABLE_INTERPRETATION -> "Processing with Gemini (table interpretation)"
        BatchPeriodicIndexJobState.PARALLEL_EMBEDDING_AND_KG_EXTRACTION -> "Generating embeddings & extracting knowledge graph"
        BatchPeriodicIndexJobState.KG_ENTITY_EMBEDDINGS -> "Generating entity embeddings"
        BatchPeriodicIndexJobState.PROCESSING_FILES -> "Uploading files to Gemini File Search"
        BatchPeriodicIndexJobState.COMPLETED -> "Completed"
        BatchPeriodicIndexJobState.FAILED -> "Failed: ${errorMessage ?: "Unknown error"}"
        BatchPeriodicIndexJobState.STOPPED -> "Stopped"
    }

    /**
     * Transition to the next stage after current stage completes successfully.
     */
    fun advanceToNextStage() {
        state = when (state) {
            BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT -> BatchPeriodicIndexJobState.CONTENT_LLM_BATCH
            BatchPeriodicIndexJobState.CONTENT_LLM_BATCH -> BatchPeriodicIndexJobState.LLM_TABLE_INTERPRETATION
            BatchPeriodicIndexJobState.LLM_TABLE_INTERPRETATION -> BatchPeriodicIndexJobState.PARALLEL_EMBEDDING_AND_KG_EXTRACTION
            BatchPeriodicIndexJobState.PARALLEL_EMBEDDING_AND_KG_EXTRACTION -> BatchPeriodicIndexJobState.KG_ENTITY_EMBEDDINGS
            BatchPeriodicIndexJobState.KG_ENTITY_EMBEDDINGS -> BatchPeriodicIndexJobState.PROCESSING_FILES
            BatchPeriodicIndexJobState.PROCESSING_FILES -> BatchPeriodicIndexJobState.COMPLETED
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
     * Add a batch job ID to the active list.
     * Sets the creation timestamp on the first job added.
     */
    fun addBatchJob(batchJobId: String) {
        batchJobIds = batchJobIds + batchJobId
        if (batchJobIds.size == 1) {
            batchJobCreatedAt = Clock.System.now()
        }
        updatedAt = Clock.System.now()
    }

    /**
     * Clear all batch job IDs after stage completes.
     */
    fun clearBatchJobs() {
        batchJobIds = emptyList()
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
    fun isWaitingForBatch(): Boolean = batchJobIds.isNotEmpty()
}
