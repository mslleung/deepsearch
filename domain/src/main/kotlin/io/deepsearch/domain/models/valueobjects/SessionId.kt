package io.deepsearch.domain.models.valueobjects

/**
 * Base class for session identifiers used to track operations across different contexts.
 * Sessions can originate from user queries or periodic index jobs.
 */
sealed class SessionId {
    abstract val value: String
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

