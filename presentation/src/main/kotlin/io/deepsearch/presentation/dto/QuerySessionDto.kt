package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.entities.QuerySession
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.SearchMode
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
    val mode: String, // "live-crawling" or "cache-only"
    val status: String, // finishReason or "IN_PROGRESS"
    val answerFound: Boolean, // Whether a meaningful answer was found
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

/**
 * DTO for image data returned in search results.
 * Images are now served via signed GCS URLs instead of inline base64.
 */
@Serializable
data class ImageDto(
    val url: String,     // Signed URL to fetch the image from GCS
    val mimeType: String // Image MIME type (e.g., "image/webp", "image/png")
)

@Serializable
data class QuerySessionDetailDto(
    val id: String,
    val query: String,
    val url: String,
    val mode: String, // "live-crawling" or "cache-only"
    val status: String,
    val answerFound: Boolean, // Whether a meaningful answer was found
    val answer: String?,
    val contentSources: List<ContentSourceDto>,
    val answerSources: List<String>,
    val exploredSources: List<String>,
    val traversedUrls: List<UrlAccessDto>,
    val images: Map<String, ImageDto> = emptyMap(), // Map of imageId -> ImageDto
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
    val modeString = when (searchMode) {
        SearchMode.LIVE_CRAWLING -> "live-crawling"
        SearchMode.CACHE_ONLY -> "cache-only"
    }
    
    return QuerySessionSummaryDto(
        id = id.value,
        query = query,
        url = url,
        mode = modeString,
        status = status,
        answerFound = answerFound,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds(),
        durationMs = durationMs,
        urlCount = urlCount
    )
}

@OptIn(ExperimentalTime::class)
fun QuerySession.toDetailDto(
    urlAccesses: List<UrlAccess>, 
    cachedWebpages: List<io.deepsearch.domain.models.entities.WebpageMarkdown> = emptyList(),
    images: Map<String, ImageDto> = emptyMap()
): QuerySessionDetailDto {
    val status = finishReason?.name ?: "IN_PROGRESS"
    val modeString = when (searchMode) {
        SearchMode.LIVE_CRAWLING -> "live-crawling"
        SearchMode.CACHE_ONLY -> "cache-only"
    }
    
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
        id = id.value,
        query = query,
        url = url,
        mode = modeString,
        status = status,
        answerFound = answerFound,
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
        images = images,
        durationMs = durationMs,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds()
    )
}

// Analytics DTOs

@Serializable
data class QuerySessionAnalyticsDto(
    val totalSessions: Int,
    val avgLiveSearchTimeMs: Long?,
    val avgStaticSearchTimeMs: Long?,
    val successRate: Double,           // % with ANSWER_COMPLETE finish reason
    val answerFoundRate: Double,       // % where answerFound = true
    val avgUrlsPerSession: Double,
    val domainStats: List<DomainStatDto>
)

@Serializable
data class DomainStatDto(
    val domain: String,
    val sessionCount: Int,
    val avgSearchTimeMs: Long?,
    val successRate: Double,
    val answerFoundRate: Double
)

