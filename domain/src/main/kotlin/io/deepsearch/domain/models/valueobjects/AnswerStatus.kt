package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Status indicating whether the answer synthesis is complete or needs more sources.
 * Used by the feedback loop to determine if additional searching is required.
 */
@Serializable
enum class AnswerStatus {
    /**
     * The answer fully addresses the query with sufficient authoritative sources.
     * No more searching is needed.
     */
    COMPLETE,

    /**
     * The answer is partial or lacks authoritative sources.
     * Additional targeted searches should be performed using the provided follow-up queries.
     */
    NEEDS_MORE_SOURCES
}

