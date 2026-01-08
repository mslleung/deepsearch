package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

/**
 * Type-safe deduplication key for (query, URL) pairs in agentic search.
 * 
 * Used to track which URLs have been processed for link discovery in the context
 * of a specific query. This enables re-analyzing the same URL with different queries
 * while preventing redundant analysis with the same query.
 * 
 * @property query The search query
 * @property normalizedUrl The normalized URL (using locale-stripping normalization for dedup)
 */
data class QueryUrlKey(
    val query: String,
    val normalizedUrl: String
)
