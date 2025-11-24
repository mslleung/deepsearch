package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Represents a source used in answer generation with its relevance score.
 * The relevance score is a composite of content relevance, temporal relevance, and authority.
 */
@Serializable
data class SourceWithRelevance(
    val url: String,
    val relevanceScore: Float
)

