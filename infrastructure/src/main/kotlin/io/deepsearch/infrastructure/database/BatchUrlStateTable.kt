package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for tracking per-URL processing state within batch periodic index jobs.
 * 
 * HTML URLs progress through stages:
 * PENDING → EXTRACTED → CONTENT_LLM_DONE → FINAL_LLM_DONE → CACHED
 * 
 * FILE URLs progress through stages:
 * PENDING → PENDING_FILE_UPLOAD → FILE_UPLOADED → CACHED
 */
class BatchUrlStateTable(
    private val databaseCryptoService: IDatabaseCryptoService
) : Table("batch_url_states") {
    val id = long("id").autoIncrement()
    val jobId = long("job_id")
    val url = varchar("url", length = 2048)
    val createdAtMs = long("created_at_ms")
    val updatedAtMs = long("updated_at_ms")
    val version = long("version").default(0)
    
    /** Current processing stage (enum name stored as string) */
    val stage = varchar("stage", length = 32).default("PENDING")
    
    // Error tracking
    val errorMessage = text("error_message").nullable()
    
    // GCS base path for snapshot data
    // Format: "batch-snapshots/{jobId}/{urlHash}"
    val snapshotBasePath = varchar("snapshot_base_path", length = 256).nullable()
    
    // Page metadata
    val title = varchar("title", length = 512).nullable()
    val description = text("description").nullable()
    
    // ═══════════ FILE-SPECIFIC COLUMNS ═══════════
    
    /** URL type: HTML or FILE */
    val urlType = varchar("url_type", length = 16).default("HTML")
    
    /** For FILE type: MIME type (e.g., "application/pdf") */
    val fileMimeType = varchar("file_mime_type", length = 128).nullable()
    
    /** For FILE type: SHA-256 hash of file content for deduplication */
    val fileHash = varchar("file_hash", length = 64).nullable()
    
    /** For FILE type: Gemini File Search document name after upload */
    val fileSearchDocumentName = varchar("file_search_document_name", length = 512).nullable()
    
    /** For FILE type: GCS storage path for temporary file bytes */
    val fileStoragePath = varchar("file_storage_path", length = 256).nullable()

    init {
        // Unique constraint on job + url
        uniqueIndex(jobId, url)
        // Index for finding URLs at each stage
        index(false, jobId, stage)
        // Index for finding file URLs by type
        index(false, jobId, urlType)
    }

    override val primaryKey = PrimaryKey(id)
}
