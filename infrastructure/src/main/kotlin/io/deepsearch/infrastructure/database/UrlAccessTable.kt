package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for storing URL access records.
 * Each row represents a single URL access attempt during a query session.
 * 
 * The status column discriminates between cached, uncached, and failed accesses,
 * enabling proper object-relational mapping to the UrlAccess sealed class hierarchy.
 */
class UrlAccessTable(
    private val querySessionTable: QuerySessionTable
) : Table("url_accesses") {
    val id = long("id").autoIncrement()
    val querySessionId = varchar("query_session_id", 255).references(querySessionTable.id)
    val url = varchar("url", 2048)
    val timestampEpochMs = long("timestamp_epoch_ms")
    val status = varchar("status", 20)  // "CACHED", "UNCACHED", "FAILED"
    val failureReason = varchar("failure_reason", 50).nullable()  // Only populated when status = "FAILED"
    
    override val primaryKey = PrimaryKey(id)
    
    init {
        // Index for querying all URL accesses by session
        index(isUnique = false, querySessionId)
        
        // Index for querying by status (e.g., all failed URLs)
        index(isUnique = false, status)
        
        // Index for temporal queries
        index(isUnique = false, timestampEpochMs)
    }
}

