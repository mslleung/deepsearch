package io.deepsearch.domain.models.valueobjects

/**
 * Result container for a single subquery executed across multiple strategies.
 */
data class SearchResult(
    val originalQuery: SearchQuery,
    val answer: String,
    val contentSources: List<MarkdownSource>,
    val answerSources: List<String>,
    val exploredSources: List<String>,
    val durationMs: Long = 0L
)
