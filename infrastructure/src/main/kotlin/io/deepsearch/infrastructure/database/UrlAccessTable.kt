package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for storing URL access records.
 * Each row represents a single URL access attempt during a session (query or periodic index).
 * 
 * The status column discriminates between cached, uncached, and failed accesses,
 * enabling proper object-relational mapping to the UrlAccess sealed class hierarchy.
 * 
 * For failed accesses, exceptionType stores the exception class name and
 * exceptionMessage stores the detailed error message.
 * 
 * The sessionId column stores either:
 * - A QuerySessionId value (e.g., "abc123")
 * - A PeriodicIndexSessionId value (e.g., "periodic-index-job-42")
 */
class UrlAccessTable : Table("url_accesses") {
    val id = long("id").autoIncrement()
    val sessionId = varchar("session_id", 255)  // Stores SessionId.value (supports both query and periodic index sessions)
    val url = varchar("url", 2048)
    val timestampEpochMs = long("timestamp_epoch_ms")
    val status = varchar("status", 20)  // "CACHED", "UNCACHED", "FAILED"
    val isUsedInAnswer = bool("is_used_in_answer").default(false)
    val exceptionType = varchar("exception_type", 100).nullable()  // Exception class name (e.g., "NetworkTimeoutException")
    val exceptionMessage = text("exception_message").nullable()  // Detailed error message
    
    override val primaryKey = PrimaryKey(id)
    
    init {
        // Index for querying all URL accesses by session
        index(isUnique = false, sessionId)
        
        // Index for querying by status (e.g., all failed URLs)
        index(isUnique = false, status)
        
        // Index for temporal queries
        index(isUnique = false, timestampEpochMs)
        
        // Index for querying by exception type
        index(isUnique = false, exceptionType)
    }
}

