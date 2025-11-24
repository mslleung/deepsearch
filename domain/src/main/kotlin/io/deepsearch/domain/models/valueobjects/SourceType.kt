package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Classification of web content source types.
 * Used to categorize sources based on their origin and purpose.
 */
@Serializable
enum class SourceType {
    /**
     * A main page (e.g., /pricing, /home, /features) intended to reflect current state.
     * These pages are typically time-insensitive and officially maintained.
     */
    OFFICIAL_LIVING_DOC,

    /**
     * A dated company update (e.g., /blog, /press, /news).
     * These pages are time-sensitive snapshots of information at a point in time.
     */
    OFFICIAL_SNAPSHOT,

    /**
     * External review or news site content.
     * Third-party perspectives and analysis.
     */
    THIRD_PARTY_REVIEW,

    /**
     * User-generated content from forums, Reddit, StackOverflow, etc.
     * Community discussions and questions.
     */
    FORUM_DISCUSSION
}


