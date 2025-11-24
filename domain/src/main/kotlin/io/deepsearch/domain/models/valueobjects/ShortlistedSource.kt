package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Represents a shortlisted source for answering a query.
 * Contains the source content and metadata about its relevance, temporal characteristics, and authority.
 */
@Serializable
data class ShortlistedSource(
    val url: String,
    val markdown: String,
    val contentRelevance: Float,  // 0.0-1.0: How much relevant information related to the query
    val temporalRelevance: Float, // 0.0-1.0: Time insensitivity (higher = more stable/official)
    val authority: Float,          // 0.0-1.0: Authority of the source (official pages > blog posts)
    val note: String               // Brief reason for inclusion in shortlist
)

