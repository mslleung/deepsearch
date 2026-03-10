package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Status indicating whether search should continue or finish.
 * Used by the feedback loop to determine if additional searching is required.
 */
@Serializable
enum class AnswerStatus {
    /**
     * The answer fully addresses the query with sufficient authoritative sources.
     * No more searching is needed.
     */
    FINISH_SEARCH,

    /**
     * The answer is partial or lacks sufficient information.
     * Search should continue to gather more information.
     */
    CONTINUE_SEARCH,

    /**
     * The sources examined do not contain the requested information.
     * Further searching on this website is unlikely to yield results.
     */
    NOT_FOUND
}
