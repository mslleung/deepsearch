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
data class ContentSourceDto(
    val url: String,
    val title: String?,
    val description: String?,
    val markdown: String
)

@Serializable
data class QuerySessionDetailDto(
    val id: String,
    val query: String,
    val url: String,
    val status: String,
    val answer: String?,
    val contentSources: List<ContentSourceDto>,
    val answerSources: List<String>,
    val exploredSources: List<String>,
    val traversedUrls: List<UrlAccessDto>,
    val durationMs: Long?,
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
fun QuerySession.toDetailDto(urlAccesses: List<UrlAccess>, cachedWebpages: List<io.deepsearch.domain.models.entities.WebpageMarkdown> = emptyList()): QuerySessionDetailDto {
    val status = finishReason?.name ?: "IN_PROGRESS"
    
    // Build a map of URL to cached webpage for efficient lookup
    val urlToWebpage = cachedWebpages.associateBy { it.url }
    
    // Content sources: all successfully accessed URLs with their content (excluding failed)
    val contentSources = urlAccesses
        .filterNot { it is FailedUrlAccess }
        .mapNotNull { urlAccess ->
            val webpage = urlToWebpage[urlAccess.url]
            val markdownContent = webpage?.markdown
            if (markdownContent != null) {
                ContentSourceDto(
                    url = urlAccess.url,
                    title = webpage.title,
                    description = webpage.description,
                    markdown = markdownContent
                )
            } else null
        }
    
    // Answer sources: URLs marked as used in answer
    val answerSources = urlAccesses
        .filter { it.isUsedInAnswer }
        .map { it.url }
    
    // Explored sources: URLs not used in answer (excluding failed)
    val exploredSources = urlAccesses
        .filterNot { it is FailedUrlAccess }
        .filterNot { it.isUsedInAnswer }
        .map { it.url }
    
    return QuerySessionDetailDto(
        id = id,
        query = query,
        url = url,
        status = status,
        answer = answer,
        contentSources = contentSources,
        answerSources = answerSources,
        exploredSources = exploredSources,
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
        durationMs = durationMs,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds()
    )
}

