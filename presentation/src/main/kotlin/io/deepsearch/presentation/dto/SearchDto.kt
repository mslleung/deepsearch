package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.valueobjects.SearchResult
import kotlinx.serialization.Serializable

@Serializable
data class SearchRequest(
    val query: String,
    val url: String,
    val sitemapUrl: String? = null,
    val cacheExpiryMs: Long? = null, // Default 7 days = 604800000ms
)

@Serializable
data class SearchContentDto(
    val url: String,
    val title: String?,
    val description: String?,
    val markdown: String
)

@Serializable
data class SearchResponse(
    val answer: String,
    val contentSources: List<SearchContentDto>,
    val answerSources: List<String>,
    val exploredSources: List<String>,
    val durationMs: Long,
    val sessionId: String? = null
)

fun SearchResult.toResponse(): SearchResponse {
    return SearchResponse(
        answer = answer,
        contentSources = contentSources.map { content ->
            SearchContentDto(
                url = content.url,
                title = content.title,
                description = content.description,
                markdown = content.markdown
            )
        },
        answerSources = answerSources,
        exploredSources = exploredSources,
        durationMs = durationMs,
        sessionId = sessionId
    )
}