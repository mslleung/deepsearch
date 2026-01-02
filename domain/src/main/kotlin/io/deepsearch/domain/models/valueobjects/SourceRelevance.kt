package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Classification of how relevant a source is to the query.
 * Used by source evaluation agents to indicate the quality of match.
 */
@Serializable
enum class SourceRelevance {
    /**
     * The source is a canonical/authoritative match for the query.
     * The page explicitly and comprehensively addresses the query topic.
     */
    CANONICAL,

    /**
     * The source partially mentions relevant information.
     * Contains some useful information but doesn't fully address the query.
     */
    PARTIAL_MENTION,

    /**
     * The source is not relevant to the query.
     * No useful information for answering the query.
     */
    NOT_RELEVANT
}


