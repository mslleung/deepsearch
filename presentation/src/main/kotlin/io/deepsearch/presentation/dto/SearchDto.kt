package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.valueobjects.SearchMode
import kotlinx.serialization.Serializable

@Serializable
data class SearchRequest(
    val query: String,
    val url: String,
    val maxCacheAge: Long? = null,
    val mode: String? = null  // "live-crawling" or "cache-only", defaults to "live-crawling"
) {
    /**
     * Parse the mode string to SearchMode enum.
     * Defaults to LIVE_CRAWLING if mode is null or invalid.
     */
    fun toSearchMode(): SearchMode {
        return when (mode?.lowercase()) {
            "cache-only" -> SearchMode.CACHE_ONLY
            "live-crawling" -> SearchMode.LIVE_CRAWLING
            null -> SearchMode.LIVE_CRAWLING
            else -> throw IllegalArgumentException("Invalid mode: '$mode'. Valid modes are: 'live-crawling', 'cache-only'")
        }
    }
}