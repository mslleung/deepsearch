package io.deepsearch.domain.models.entities

import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.SemanticElements
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
 * Data flows through stages:
 * - Stage 1 (Extract): Populates html, cleanedHtml
 * - Stage 2 (Content LLM): Populates semanticElements, tableIdentifications
 * - Stage 3 (Final LLM): Populates tableMarkdowns
 * - Stage 4 (Cache): Uses all data to build final markdown
 */
/**
 * Serializable icon data for batch storage.
 * Icons are deduplicated by hash across the batch job.
 */
@kotlinx.serialization.Serializable
data class BatchIconData(
    /** Base64-encoded icon bytes */
    val bytesBase64: String,
    /** MIME type of the icon */
    val mimeType: String,
    /** CSS selectors for this icon in the DOM */
    val cssSelectors: List<String>,
    /** SHA-256 hash of the bytes (base64 encoded) for deduplication */
    val hashBase64: String
)

/**
 * Serializable image data for batch storage.
 * Images are deduplicated by hash across the batch job.
 */
@kotlinx.serialization.Serializable
data class BatchImageData(
    /** Base64-encoded image bytes */
    val bytesBase64: String,
    /** MIME type of the image */
    val mimeType: String,
    /** CSS selectors for this image in the DOM */
    val cssSelectors: List<String>,
    /** SHA-256 hash of the bytes (base64 encoded) for deduplication */
    val hashBase64: String
)

@kotlinx.serialization.Serializable
data class BatchUrlSnapshotData(
    /** Raw HTML from browser extraction (Stage 1) */
    val html: String,
    
    /**
     * Bounding boxes for all elements from browser extraction (Stage 1).
     * Maps XPath -> BoundingBox. Used in Stage 3 for table interpretation.
     */
    val boundingBoxes: Map<String, IBrowserPage.BoundingBox>? = null,
    
    /**
     * Icons extracted from the page (Stage 1).
     * Used in Stage 2 for icon interpretation.
     */
    val icons: List<BatchIconData>? = null,
    
    /**
     * Images extracted from the page (Stage 1).
     * Used in Stage 2 for image text extraction.
     */
    val images: List<BatchImageData>? = null,
    
    /** Cleaned HTML with stable identifiers injected for LLM processing (Stage 2) */
    val cleanedHtml: String? = null,
    
    /** 
     * Stage 2 result: Identified semantic elements (header, footer, nav, etc.)
     * Used to remove non-content elements before text extraction.
     */
    val semanticElements: SemanticElements? = null,
    
    /**
     * Stage 2 result: Identified tables with their stable data-ds-id values.
     * Used in Stage 3 for table interpretation.
     */
    val tableIdentifications: List<TableIdentification>? = null,
    
    /**
     * Stage 2 result: Interpreted icon labels.
     * Maps icon hash (base64) -> interpreted label (or null if not interpretable).
     */
    val iconInterpretations: Map<String, String?>? = null,
    
    /**
     * Stage 2 result: Extracted image text.
     * Maps image hash (base64) -> extracted text (or null if no text).
     */
    val imageTexts: Map<String, String?>? = null,
    
    /**
     * Stage 3 result: Markdown interpretation for each table.
     * Maps table dataId -> markdown representation.
     */
    val tableMarkdowns: Map<String, String>? = null,
    
    /** 
     * Final markdown content assembled from all processing stages.
     * Generated during cache write stage (Stage 4).
     */
    val markdown: String? = null,
    
    /**
     * Batch request index for tracking responses.
     * Used to match batch results back to this URL.
     */
    val batchRequestIndex: Int? = null
)
