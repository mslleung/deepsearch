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
    val title: String?,
    val description: String?,
    val contentSummary: String?  // Brief summary of what the page is about
) {
    /**
     * Returns a summary string for prompts.
     * Example: "Stripe Pricing - Payment processing fees (stripe.com/pricing)"
     */
    fun toPromptSummary(): String = buildString {
        if (!title.isNullOrBlank()) {
            append(title)
        }
        if (!description.isNullOrBlank()) {
            if (isNotEmpty()) append(" - ")
            append(description.take(200))
        }
        if (contentSummary != null && isNotEmpty()) {
            append(" | ")
            append(contentSummary.take(300))
        }
    }
}

/**
 * Cached website context with timestamp for cache expiration.
 */
@OptIn(ExperimentalTime::class)
data class CachedWebsiteContext(
    val url: String,
    val title: String?,
    val description: String?,
    val contentSummary: String?,
    val cachedAt: Instant
) {
    fun toWebsiteContext(): WebsiteContext = WebsiteContext(
        url = url,
        title = title,
        description = description,
        contentSummary = contentSummary
    )
}

