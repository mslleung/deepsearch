package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for caching PDF source evaluation results.
 * 
 * Uses content hash (Base64-encoded SHA-256) as primary key to enable
 * fast lookups and deduplication of LLM evaluation results.
 */
class PdfSourceEvalCacheTable : Table("pdf_source_eval_cache") {
    /** Base64-encoded SHA-256 hash of (query + extractedText) */
    val contentHash = varchar("content_hash", length = 64)
    
    /** Serialized EvaluatedSource JSON, or null if source was not relevant */
    val evaluatedSourceJson = text("evaluated_source_json").nullable()
    
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    init {
        index(true, contentHash)
    }

    override val primaryKey = PrimaryKey(contentHash)
}
