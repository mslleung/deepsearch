package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Type of URL content - determines processing path.
 */
enum class BatchUrlType {
    /** HTML page - goes through browser extraction + LLM processing */
    HTML,
    /** File (PDF, docx, etc.) - goes through Gemini File Search upload */
    FILE
}

/**
 * Processing stage for a URL within a batch periodic index job.
 * URLs progress through these stages based on their type:
 * 
 * HTML Track:  PENDING → EXTRACTED → CONTENT_LLM_DONE → FINAL_LLM_DONE → CACHED
 * FILE Track:  PENDING → PENDING_FILE_UPLOAD → FILE_UPLOADED → CACHED
 */
enum class BatchUrlProcessingStage {
    /** URL discovered but not yet processed */
    PENDING,
    
    // ═══════════ HTML TRACK ═══════════
    /** Stage 1 complete: Browser extraction done (crawl + extract in single visit) */
    EXTRACTED,
    /** Stage 2 complete: Content LLM processing done */
    CONTENT_LLM_DONE,
    /** Stage 3 complete: Final LLM processing done */
    FINAL_LLM_DONE,
    
    // ═══════════ FILE TRACK ═══════════
    /** Stage 1 complete: File downloaded, awaiting upload to Gemini File Search */
    PENDING_FILE_UPLOAD,
    /** File uploaded to Gemini File Search, ready for embedding/caching */
    FILE_UPLOADED,
    
    // ═══════════ CONVERGED ═══════════
    /** Stage 4 complete: Written to cache */
    CACHED
}

/**
 * Processing state for a single URL within a batch periodic index job.
 * 
 * Tracks progress through each stage to enable resumption after server restarts.
 * 
 * HTML URLs progress through stages:
 * 1. PENDING → EXTRACTED (browser crawl + extraction)
 * 2. EXTRACTED → CONTENT_LLM_DONE (semantic/table/icon identification)
 * 3. CONTENT_LLM_DONE → FINAL_LLM_DONE (table interpretation)
 * 4. FINAL_LLM_DONE → CACHED (written to search index)
 * 
 * FILE URLs progress through stages:
 * 1. PENDING → PENDING_FILE_UPLOAD (file downloaded)
 * 2. PENDING_FILE_UPLOAD → FILE_UPLOADED (uploaded to Gemini File Search)
 * 3. FILE_UPLOADED → CACHED (written to search index)
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
     * GCS base path for snapshot data stored in cloud storage.
     * Format: "batch-snapshots/{jobId}/{urlHash}"
     * All intermediate data (HTML, screenshots, icons, images, LLM results)
     * is stored in GCS under this path instead of in the database.
     * Cleared after successful caching when GCS files are deleted.
     */
    var snapshotBasePath: String? = null,
    
    /**
     * Page title extracted during browser extraction.
     */
    var title: String? = null,
    
    /**
     * Page description extracted during browser extraction.
     */
    var description: String? = null,
    
    // ═══════════ FILE-SPECIFIC FIELDS ═══════════
    
    /**
     * Type of URL content (HTML or FILE).
     * Determines which processing track this URL follows.
     */
    var urlType: BatchUrlType = BatchUrlType.HTML,
    
    /**
     * For FILE type: MIME type of the file (e.g., "application/pdf").
     */
    var fileMimeType: String? = null,
    
    /**
     * For FILE type: SHA-256 hash of file content for deduplication.
     */
    var fileHash: String? = null,
    
    /**
     * For FILE type: Gemini File Search document name after upload.
     * Format: "fileSearchStores/{store}/documents/{doc}"
     */
    var fileSearchDocumentName: String? = null,
    
    /**
     * For FILE type: GCS storage path for temporary file storage.
     * Format: "batch-files/{jobId}/{fileHash}"
     * Cleared after successful upload to Gemini File Search.
     */
    var fileStoragePath: String? = null
) {
    /**
     * Mark URL as extracted (crawled + browser extracted in single visit).
     * For HTML type URLs only.
     * 
     * @param basePath GCS base path where snapshot data is stored (e.g., "batch-snapshots/123/abc456")
     * @param pageTitle Page title extracted from the page
     * @param pageDescription Page description extracted from the page
     */
    fun markExtracted(basePath: String, pageTitle: String?, pageDescription: String?) {
        urlType = BatchUrlType.HTML
        stage = BatchUrlProcessingStage.EXTRACTED
        snapshotBasePath = basePath
        title = pageTitle
        description = pageDescription
        updatedAt = Clock.System.now()
    }
    
    /**
     * Mark URL as a file pending upload to Gemini File Search.
     * For FILE type URLs only.
     * 
     * @param mimeType MIME type of the file
     * @param hash SHA-256 hash of file content
     * @param storagePath GCS path where file bytes are stored (e.g., "batch-files/{jobId}/{hash}")
     * @param fileName Optional file name for display
     */
    fun markPendingFileUpload(mimeType: String, hash: String, storagePath: String, fileName: String?) {
        urlType = BatchUrlType.FILE
        stage = BatchUrlProcessingStage.PENDING_FILE_UPLOAD
        fileMimeType = mimeType
        fileHash = hash
        fileStoragePath = storagePath
        title = fileName
        updatedAt = Clock.System.now()
    }
    
    /**
     * Mark URL as uploaded to Gemini File Search.
     * For FILE type URLs only.
     * 
     * Note: fileStoragePath is NOT cleared here - caller should delete from GCS
     * after this method returns successfully.
     */
    fun markFileUploaded(documentName: String) {
        stage = BatchUrlProcessingStage.FILE_UPLOADED
        fileSearchDocumentName = documentName
        fileStoragePath = null // Path cleared after successful upload
        updatedAt = Clock.System.now()
    }

    /**
     * Mark URL as content LLM processed.
     * Snapshot data remains in GCS at the same base path.
     */
    fun markContentLlmDone() {
        stage = BatchUrlProcessingStage.CONTENT_LLM_DONE
        updatedAt = Clock.System.now()
    }

    /**
     * Mark URL as final LLM processed.
     * Snapshot data remains in GCS at the same base path.
     */
    fun markFinalLlmDone() {
        stage = BatchUrlProcessingStage.FINAL_LLM_DONE
        updatedAt = Clock.System.now()
    }

    /**
     * Mark URL as cached and clear snapshot base path.
     * Caller should delete GCS files before calling this.
     */
    fun markCached() {
        stage = BatchUrlProcessingStage.CACHED
        snapshotBasePath = null // GCS files should be deleted by caller
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
     * Check if this URL has been extracted (stage 1 complete for HTML).
     */
    fun isExtracted(): Boolean = stage == BatchUrlProcessingStage.EXTRACTED ||
        stage == BatchUrlProcessingStage.CONTENT_LLM_DONE ||
        stage == BatchUrlProcessingStage.FINAL_LLM_DONE ||
        stage == BatchUrlProcessingStage.CACHED
    
    /**
     * Check if this URL is a file type.
     */
    fun isFile(): Boolean = urlType == BatchUrlType.FILE
    
    /**
     * Check if this URL is an HTML type.
     */
    fun isHtml(): Boolean = urlType == BatchUrlType.HTML
    
    /**
     * Check if this file URL is ready for embedding/caching.
     * Returns true if file has been uploaded to Gemini File Search.
     */
    fun isFileReadyForCaching(): Boolean = 
        urlType == BatchUrlType.FILE && stage == BatchUrlProcessingStage.FILE_UPLOADED
    
    /**
     * Check if this HTML URL is ready for embedding/caching.
     * Returns true if all LLM processing is complete.
     */
    fun isHtmlReadyForCaching(): Boolean = 
        urlType == BatchUrlType.HTML && stage == BatchUrlProcessingStage.FINAL_LLM_DONE

    /**
     * Get the current stage number for this URL.
     * HTML track: 1-4
     * FILE track: 1-3
     */
    fun currentStageNumber(): Int = when (stage) {
        BatchUrlProcessingStage.PENDING -> 1
        // HTML track
        BatchUrlProcessingStage.EXTRACTED -> 2
        BatchUrlProcessingStage.CONTENT_LLM_DONE -> 3
        BatchUrlProcessingStage.FINAL_LLM_DONE -> 4
        // FILE track
        BatchUrlProcessingStage.PENDING_FILE_UPLOAD -> 2
        BatchUrlProcessingStage.FILE_UPLOADED -> 3
        // Converged
        BatchUrlProcessingStage.CACHED -> 4
    }
}
