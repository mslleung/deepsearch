package io.deepsearch.domain.models.valueobjects

/**
 * Result container for a single subquery executed across multiple strategies.
 */
data class SearchResult(
    val originalQuery: SearchQuery,
    val content: String,
    val sources: List<String>
)
