package io.deepsearch.presentation.admin.dto

import io.deepsearch.domain.models.entities.QuerySession
 import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.UncachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Serializable
data class AdminQuerySessionDto(
    val id: String,
    val query: String,
    val url: String,
    val finishReason: String?,
    val answer: String?,
    val totalUrlsAccessed: Int,
    val cachedUrlCount: Int,
    val uncachedUrlCount: Int,
    val failedUrlCount: Int,
    val createdAt: Long, // epoch millis
    val updatedAt: Long
)

@OptIn(ExperimentalTime::class)
fun QuerySession.toAdminDto(urlAccesses: List<io.deepsearch.domain.models.valueobjects.UrlAccess>): AdminQuerySessionDto {
    // Use type-based filtering with filterIsInstance for DDD sealed class
    val cachedCount = urlAccesses.filterIsInstance<CachedUrlAccess>().size
    val uncachedCount = urlAccesses.filterIsInstance<UncachedUrlAccess>().size
    val failedCount = urlAccesses.filterIsInstance<FailedUrlAccess>().size
    
    return AdminQuerySessionDto(
        id = this.id.value,
        query = this.query,
        url = this.url,
        finishReason = this.finishReason?.name,
        answer = this.answer,
        totalUrlsAccessed = urlAccesses.size,
        cachedUrlCount = cachedCount,
        uncachedUrlCount = uncachedCount,
        failedUrlCount = failedCount,
        createdAt = this.createdAt.toEpochMilliseconds(),
        updatedAt = this.updatedAt.toEpochMilliseconds()
    )
}

@Serializable
data class UrlAccessDto(
    val url: String,
    val timestamp: Long,
    val type: String,  // "CACHED", "UNCACHED", "FAILED"
    val exceptionType: String? = null,  // Exception class name (e.g., "NetworkTimeoutException"), only for FAILED type
    val exceptionMessage: String? = null  // Detailed error message, only for FAILED type
)

@Serializable
data class AdminQuerySessionDetailDto(
    val id: String,
    val query: String,
    val url: String,
    val finishReason: String?,
    val answer: String?,
    val traversedUrls: List<UrlAccessDto>,
    val createdAt: Long,
    val updatedAt: Long
)

@OptIn(ExperimentalTime::class)
fun QuerySession.toAdminDetailDto(urlAccesses: List<io.deepsearch.domain.models.valueobjects.UrlAccess>): AdminQuerySessionDetailDto {
    return AdminQuerySessionDetailDto(
        id = this.id.value,
        query = this.query,
        url = this.url,
        finishReason = this.finishReason?.name,
        answer = this.answer,
        traversedUrls = urlAccesses.map { urlAccess ->
            UrlAccessDto(
                url = urlAccess.url,
                timestamp = urlAccess.timestamp.toEpochMilliseconds(),
                type = when (urlAccess) {
                    is CachedUrlAccess -> "CACHED"
                    is UncachedUrlAccess -> "UNCACHED"
                    is FailedUrlAccess -> "FAILED"
                },
                exceptionType = (urlAccess as? FailedUrlAccess)?.exceptionType,
                exceptionMessage = (urlAccess as? FailedUrlAccess)?.message
            )
        },
        createdAt = this.createdAt.toEpochMilliseconds(),
        updatedAt = this.updatedAt.toEpochMilliseconds()
    )
}

