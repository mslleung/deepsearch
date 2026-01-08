package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.domain.models.valueobjects.WebpageLink

/**
 * A WebpageLink discovered in the context of a specific query during agentic search.
 * 
 * This class exists to separate concerns between:
 * - WebpageLink (domain): Pure link representation used in periodic indexing and general crawling
 * - DiscoveredLink (application): Link with query context for agentic search deduplication
 * 
 * The query context enables two-level deduplication:
 * - (query, URL) dedup: Prevents re-analyzing the same URL with the same query
 * - URL dedup: Ensures content is fetched/emitted only once per URL
 * 
 * @property link The underlying WebpageLink with URL, source, reason, and score
 * @property query The query that led to discovering this link
 */
data class DiscoveredLink(
    val link: WebpageLink,
    val query: String
) {
    /** Convenience accessor for the URL */
    val url: String get() = link.url
    
    /** Convenience accessor for the score (for priority queue ordering) */
    val score: Int? get() = link.score
}
