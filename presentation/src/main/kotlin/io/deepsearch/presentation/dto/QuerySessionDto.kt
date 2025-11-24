package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.entities.QuerySession
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.UncachedUrlAccess
import io.deepsearch.domain.models.valueobjects.UrlAccess
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@Serializable
data class QuerySessionListResponse(
    val sessions: List<QuerySessionSummaryDto>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Int
)

@Serializable
data class QuerySessionSummaryDto(
    val id: String,
    val query: String,
    val url: String,
    val status: String, // finishReason or "IN_PROGRESS"
    val createdAt: Long,
    val updatedAt: Long,
    val durationMs: Long?,
    val urlCount: Int
)

@Serializable
data class QuerySessionDetailDto(
    val id: String,
    val query: String,
    val url: String,
    val status: String,
    val answer: String?,
    val traversedUrls: List<UrlAccessDto>,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class UrlAccessDto(
    val url: String,
    val timestamp: Long,
    val type: String,  // "CACHED", "UNCACHED", "FAILED"
    val exceptionType: String? = null,
    val exceptionMessage: String? = null
)

@OptIn(ExperimentalTime::class)
fun QuerySession.toSummaryDto(urlCount: Int): QuerySessionSummaryDto {
    val status = finishReason?.name ?: "IN_PROGRESS"
    val durationMs = if (finishReason != null) {
        (updatedAt - createdAt).inWholeMilliseconds
    } else {
        null
    }
    
    return QuerySessionSummaryDto(
        id = id,
        query = query,
        url = url,
        status = status,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds(),
        durationMs = durationMs,
        urlCount = urlCount
    )
}

@OptIn(ExperimentalTime::class)
fun QuerySession.toDetailDto(urlAccesses: List<UrlAccess>): QuerySessionDetailDto {
    val status = finishReason?.name ?: "IN_PROGRESS"
    
    return QuerySessionDetailDto(
        id = id,
        query = query,
        url = url,
        status = status,
        answer = answer,
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
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds()
    )
}

