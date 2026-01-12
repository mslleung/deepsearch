package io.deepsearch.domain.models.valueobjects

/**
 * Base class for session identifiers used to track operations across different contexts.
 * Sessions can originate from user queries, periodic index jobs, or batch periodic index jobs.
 */
sealed class SessionId {
    abstract val value: String

    /**
     * Convert this SessionId to a string suitable for storage.
     * Can be reconstructed using [fromStorageString].
     */
    fun toStorageString(): String = when (this) {
        is QuerySessionId -> "query:$value"
        is PeriodicIndexSessionId -> "periodic:$jobId"
        is BatchPeriodicIndexSessionId -> "batch:$jobId"
    }

    companion object {
        private val PERIODIC_PATTERN = Regex("""periodic:(\d+)""")
        private val BATCH_PATTERN = Regex("""batch:(\d+)""")
        private val QUERY_PATTERN = Regex("""query:(.+)""")

        /**
         * Reconstruct a SessionId from its storage string representation.
         * @throws IllegalArgumentException if the format is invalid
         */
        fun fromStorageString(storage: String): SessionId {
            PERIODIC_PATTERN.matchEntire(storage)?.let { match ->
                return PeriodicIndexSessionId(match.groupValues[1].toLong())
            }
            BATCH_PATTERN.matchEntire(storage)?.let { match ->
                return BatchPeriodicIndexSessionId(match.groupValues[1].toLong())
            }
            QUERY_PATTERN.matchEntire(storage)?.let { match ->
                return QuerySessionId(match.groupValues[1])
            }
            // Fallback: treat as query session ID for backward compatibility
            return QuerySessionId(storage)
        }
    }
}

/**
 * Session ID for user-initiated query sessions.
 */
data class QuerySessionId(override val value: String) : SessionId() {
    init {
        require(value.isNotBlank()) { "QuerySessionId must not be blank" }
    }
}

/**
 * Session ID for periodic index job sessions.
 */
data class PeriodicIndexSessionId(val jobId: Long) : SessionId() {
    override val value: String get() = "periodic-index-job-$jobId"
}

/**
 * Session ID for batch periodic index job sessions.
 * Used for cost tracking of batch API operations.
 */
data class BatchPeriodicIndexSessionId(val jobId: Long) : SessionId() {
    override val value: String get() = "batch-periodic-index-job-$jobId"
}
