package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for tracking per-URL processing state within batch periodic index jobs.
 * 
 * Each URL progresses through stages:
 * PENDING → EXTRACTED → CONTENT_LLM_DONE → FINAL_LLM_DONE → CACHED
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
    
    // Intermediate data (JSON blob)
    val snapshotData = text("snapshot_data").nullable()
    
    // Page metadata
    val title = varchar("title", length = 512).nullable()
    val description = text("description").nullable()

    init {
        // Unique constraint on job + url
        uniqueIndex(jobId, url)
        // Index for finding URLs at each stage
        index(false, jobId, stage)
    }

    override val primaryKey = PrimaryKey(id)
}
