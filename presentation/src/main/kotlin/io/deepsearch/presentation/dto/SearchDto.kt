package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.valueobjects.SearchResult
import kotlinx.serialization.Serializable

@Serializable
data class SearchRequest(
    val query: String,
    val url: String,
    val sitemapUrl: String? = null,
)

@Serializable
data class SearchResponse(
    val answer: String,
    val content: String,
    val sources: List<String>,
)

fun SearchResult.toResponse(): SearchResponse {
    return SearchResponse(
        answer = answer,
        content = content,
        sources = sources
    )
}