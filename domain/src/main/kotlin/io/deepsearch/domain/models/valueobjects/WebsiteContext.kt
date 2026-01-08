package io.deepsearch.domain.models.valueobjects

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Context about a specific webpage to inform query understanding.
 * Provides the scope/domain that helps interpret ambiguous queries.
 * 
 * Cached per URL to avoid re-fetching for subsequent queries on the same page.
 */
data class WebsiteContext(
    val url: String,
    val contentSummary: String  // Summary of what the page is about
) {
    /**
     * Returns a summary string for prompts.
     */
    fun toPromptSummary(): String = contentSummary.take(500)
}

/**
 * Cached website context with timestamp for cache expiration.
 */
@OptIn(ExperimentalTime::class)
data class CachedWebsiteContext(
    val url: String,
    val contentSummary: String,
    val cachedAt: Instant
) {
    fun toWebsiteContext(): WebsiteContext = WebsiteContext(
        url = url,
        contentSummary = contentSummary
    )
}

