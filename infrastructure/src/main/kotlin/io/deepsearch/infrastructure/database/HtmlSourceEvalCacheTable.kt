package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for caching HTML source evaluation results.
 * 
 * Uses content hash (Base64-encoded SHA-256) as primary key to enable
 * fast lookups and deduplication of LLM evaluation results.
 */
class HtmlSourceEvalCacheTable : Table("html_source_eval_cache") {
    /** Base64-encoded SHA-256 hash of (query + cleanedHtml) */
    val contentHash = varchar("content_hash", length = 64)
    
    /** Serialized EvaluatedSource JSON, or null if source was not relevant */
    val evaluatedSourceJson = text("evaluated_source_json").nullable()
    
    /** Token usage metrics for reporting even on cache hits */
    val promptTokens = integer("prompt_tokens")
    val outputTokens = integer("output_tokens")
    val totalTokens = integer("total_tokens")
    
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    init {
        index(true, contentHash)
    }

    override val primaryKey = PrimaryKey(contentHash)
}

