package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Processing stage for a URL within a batch periodic index job.
 * URLs progress through these stages sequentially.
 */
enum class BatchUrlProcessingStage {
    /** URL discovered but not yet processed */
    PENDING,
    /** Stage 1 complete: Browser extraction done (crawl + extract in single visit) */
    EXTRACTED,
    /** Stage 2 complete: Content LLM processing done */
    CONTENT_LLM_DONE,
    /** Stage 3 complete: Final LLM processing done */
    FINAL_LLM_DONE,
    /** Stage 4 complete: Written to cache */
    CACHED
}

/**
 * Processing state for a single URL within a batch periodic index job.
 * 
 * Tracks progress through each stage to enable resumption after server restarts.
 * Each URL progresses through stages:
 * 1. PENDING → EXTRACTED (browser crawl + extraction)
 * 2. EXTRACTED → CONTENT_LLM_DONE (semantic/table/icon identification)
 * 3. CONTENT_LLM_DONE → FINAL_LLM_DONE (table interpretation)
 * 4. FINAL_LLM_DONE → CACHED (written to search index)
 */
@OptIn(ExperimentalTime::class)
class BatchUrlState(
    var id: Long? = null,
    val jobId: Long,
    val url: String,
    val createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now(),
    var version: Long = 0,
    
    /** Current processing stage */
    var stage: BatchUrlProcessingStage = BatchUrlProcessingStage.PENDING,
    
    /**
     * Error message if processing failed for this URL.
     * URLs with errors are skipped but don't fail the entire job.
     */
    var errorMessage: String? = null,
    
    /**
     * JSON blob storing intermediate data between stages.
     * Contains HTML, snapshots, LLM results, etc.
     * Cleared after successful caching to save space.
     */
    var snapshotData: String? = null,
    
    /**
     * Page title extracted during browser extraction.
     */
    var title: String? = null,
    
    /**
     * Page description extracted during browser extraction.
     */
    var description: String? = null
) {
    /**
     * Mark URL as extracted (crawled + browser extracted in single visit).
     */
    fun markExtracted(snapshot: String, pageTitle: String?, pageDescription: String?) {
        stage = BatchUrlProcessingStage.EXTRACTED
        snapshotData = snapshot
        title = pageTitle
        description = pageDescription
        updatedAt = Clock.System.now()
    }

    /**
     * Mark URL as content LLM processed with updated snapshot data.
     */
    fun markContentLlmDone(updatedSnapshot: String) {
        stage = BatchUrlProcessingStage.CONTENT_LLM_DONE
        snapshotData = updatedSnapshot
        updatedAt = Clock.System.now()
    }

    /**
     * Mark URL as final LLM processed with updated snapshot data.
     */
    fun markFinalLlmDone(updatedSnapshot: String) {
        stage = BatchUrlProcessingStage.FINAL_LLM_DONE
        snapshotData = updatedSnapshot
        updatedAt = Clock.System.now()
    }

    /**
     * Mark URL as cached and clear snapshot data.
     */
    fun markCached() {
        stage = BatchUrlProcessingStage.CACHED
        snapshotData = null // Clear to save space
        updatedAt = Clock.System.now()
    }

    /**
     * Mark URL as failed with an error message.
     */
    fun markFailed(message: String) {
        errorMessage = message
        updatedAt = Clock.System.now()
    }

    /**
     * Check if this URL has completed all processing.
     */
    fun isComplete(): Boolean = stage == BatchUrlProcessingStage.CACHED || errorMessage != null

    /**
     * Check if this URL failed.
     */
    fun isFailed(): Boolean = errorMessage != null

    /**
     * Check if this URL has been extracted (stage 1 complete).
     */
    fun isExtracted(): Boolean = stage >= BatchUrlProcessingStage.EXTRACTED

    /**
     * Get the current stage number for this URL (1-4).
     */
    fun currentStageNumber(): Int = when (stage) {
        BatchUrlProcessingStage.PENDING -> 1
        BatchUrlProcessingStage.EXTRACTED -> 2
        BatchUrlProcessingStage.CONTENT_LLM_DONE -> 3
        BatchUrlProcessingStage.FINAL_LLM_DONE -> 4
        BatchUrlProcessingStage.CACHED -> 4
    }
}

/**
 * Intermediate data stored in snapshotData JSON.
 * Contains all information needed for subsequent stages.
 * 
 * Simplified for batch processing - just stores HTML and final markdown.
 * LLM processing happens inline (not truly batched) until the Gemini Batch API
 * is available in the java-genai SDK.
 */
@kotlinx.serialization.Serializable
data class BatchUrlSnapshotData(
    /** Raw HTML from browser extraction */
    val html: String,
    /** Final markdown content (generated during cache write stage) */
    val markdown: String? = null
)
