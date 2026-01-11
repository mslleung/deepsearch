package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.BatchUrlProcessingStage
import io.deepsearch.domain.models.entities.BatchUrlState

/**
 * Repository interface for BatchUrlState persistence.
 * 
 * Tracks per-URL processing state within a batch periodic index job.
 * Enables resumption of jobs after server restarts.
 */
interface IBatchUrlStateRepository {
    /**
     * Create a new URL state entry.
     * @return The created entry with assigned ID
     */
    suspend fun create(urlState: BatchUrlState): BatchUrlState

    /**
     * Batch create multiple URL state entries.
     * More efficient than individual creates for large batches.
     */
    suspend fun batchCreate(urlStates: List<BatchUrlState>)

    /**
     * Update an existing URL state entry.
     * Uses optimistic locking via version field.
     */
    suspend fun update(urlState: BatchUrlState)

    /**
     * Batch update multiple URL state entries.
     */
    suspend fun batchUpdate(urlStates: List<BatchUrlState>)

    /**
     * Find a URL state by ID.
     */
    suspend fun findById(id: Long): BatchUrlState?

    /**
     * Find a URL state by job ID and URL.
     */
    suspend fun findByJobIdAndUrl(jobId: Long, url: String): BatchUrlState?

    /**
     * Find all URL states for a job.
     */
    suspend fun findByJobId(jobId: Long): List<BatchUrlState>

    /**
     * Find all URL states for a job at a specific stage.
     */
    suspend fun findByJobIdAndStage(jobId: Long, stage: BatchUrlProcessingStage): List<BatchUrlState>

    /**
     * Find all URL states for a job that need content LLM processing.
     * (stage=EXTRACTED, no error)
     */
    suspend fun findNeedingContentLlmProcessing(jobId: Long): List<BatchUrlState>

    /**
     * Find all URL states for a job that need final LLM processing.
     * (stage=CONTENT_LLM_DONE, no error)
     */
    suspend fun findNeedingFinalLlmProcessing(jobId: Long): List<BatchUrlState>

    /**
     * Find all URL states for a job that need caching.
     * (stage=FINAL_LLM_DONE, no error)
     */
    suspend fun findNeedingCaching(jobId: Long): List<BatchUrlState>
    
    /**
     * Find all FILE URLs that are pending upload to Gemini File Search.
     * (stage=PENDING_FILE_UPLOAD, no error)
     */
    suspend fun findPendingFileUploads(jobId: Long): List<BatchUrlState>
    
    /**
     * Find all FILE URLs that have been uploaded and are ready for caching.
     * (stage=FILE_UPLOADED, no error)
     */
    suspend fun findUploadedFilesNeedingCaching(jobId: Long): List<BatchUrlState>

    /**
     * Count URLs by stage for a job.
     */
    suspend fun countByStage(jobId: Long): BatchUrlStageCounts

    /**
     * Delete all URL states for a job.
     * Used when cleaning up a cancelled/failed job.
     */
    suspend fun deleteByJobId(jobId: Long)

    /**
     * Check if a URL already exists for a job.
     */
    suspend fun existsByJobIdAndUrl(jobId: Long, url: String): Boolean
}

/**
 * Counts of URLs at each processing stage.
 */
data class BatchUrlStageCounts(
    val total: Int,
    val pending: Int,
    // HTML track
    val extracted: Int,
    val contentLlmDone: Int,
    val finalLlmDone: Int,
    // FILE track
    val pendingFileUpload: Int,
    val fileUploaded: Int,
    // Converged
    val cached: Int,
    val failed: Int
) {
    /** Total HTML URLs */
    val totalHtml: Int get() = extracted + contentLlmDone + finalLlmDone
    
    /** Total FILE URLs */
    val totalFiles: Int get() = pendingFileUpload + fileUploaded
    
    /** HTML URLs ready for caching */
    val htmlReadyForCaching: Int get() = finalLlmDone
    
    /** FILE URLs ready for caching */
    val filesReadyForCaching: Int get() = fileUploaded
}
