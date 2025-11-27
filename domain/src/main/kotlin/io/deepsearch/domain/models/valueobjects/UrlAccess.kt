package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Represents an access to a URL during a session (query or periodic index).
 * Sealed class hierarchy provides type safety and encapsulates access-specific behavior.
 * 
 * Following DDD principles, the type itself indicates the cache status,
 * and failure reasons are encapsulated within FailedUrlAccess.
 * 
 * Note: The sealed class itself is not serializable; subclasses handle serialization individually.
 */
@OptIn(ExperimentalTime::class)
sealed class UrlAccess {
    abstract val url: String
    abstract val timestamp: Instant
    abstract val isUsedInAnswer: Boolean
}

/**
 * URL was served from cache - fast retrieval without processing.
 */
@OptIn(ExperimentalTime::class)
@Serializable
data class CachedUrlAccess(
    override val url: String,
    override val timestamp: Instant,
    override val isUsedInAnswer: Boolean = false
) : UrlAccess()

/**
 * URL was processed on-the-spot - full extraction and processing performed.
 */
@OptIn(ExperimentalTime::class)
@Serializable
data class UncachedUrlAccess(
    override val url: String,
    override val timestamp: Instant,
    override val isUsedInAnswer: Boolean = false
) : UrlAccess()

/**
 * URL processing failed - includes the exception type and message.
 * Type safety ensures failed URLs always have associated error information.
 * 
 * @property exceptionType The simple class name of the exception (e.g., "NetworkTimeoutException")
 * @property message The error message describing what went wrong
 */
@OptIn(ExperimentalTime::class)
@Serializable
data class FailedUrlAccess(
    override val url: String,
    override val timestamp: Instant,
    override val isUsedInAnswer: Boolean = false,
    val exceptionType: String,
    val message: String
) : UrlAccess()
