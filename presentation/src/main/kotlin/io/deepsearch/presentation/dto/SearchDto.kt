package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.valueobjects.SearchResult
import kotlinx.serialization.Serializable

@Serializable
data class SearchRequest(
    val query: String,
    val url: String,
    val sitemapUrl: String? = null,
    val maxUrls: Int? = null,
    val searchDurationSeconds: Int? = null,
    val cacheExpiryMs: Long? = null, // Default 7 days = 604800000ms
)

@Serializable
data class SourceWithRelevance(
    val url: String,
    val relevanceScore: Float
)

@Serializable
data class SearchResponse(
    val answer: String,
    val content: String,
    val answerSources: List<SourceWithRelevance>,
    val exploredSources: List<String>,
    val durationMs: Long,
)

fun SearchResult.toResponse(): SearchResponse {
    return SearchResponse(
        answer = answer,
        content = content,
        answerSources = answerSources.map { 
            SourceWithRelevance(
                url = it.url,
                relevanceScore = it.relevanceScore
            )
        },
        exploredSources = exploredSources,
        durationMs = durationMs
    )
}