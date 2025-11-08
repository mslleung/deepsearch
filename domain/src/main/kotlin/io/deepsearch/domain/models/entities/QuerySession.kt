package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.SearchBudget
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Represents a search query session with state tracking for coordinating
 * background link traversal and foreground answer generation.
 *
 * This is a rich domain model that encapsulates business logic for state transitions
 * and state management, following DDD principles.
 */
@OptIn(ExperimentalTime::class)
class QuerySession(
    val id: String,
    val query: String,
    val url: String,
    var state: QuerySessionState,
    var searchBudget: SearchBudget,
    var finishReason: FinishReason?,
    var answer: String?,
    val createdAt: Instant,
    var updatedAt: Instant,
    var version: Long = 0
) {

    constructor(id: String, query: String, url: String) : this(
        id = id,
        query = query,
        url = url,
        state = QuerySessionState.EXPANDING_QUERY,
        searchBudget = SearchBudget(),
        finishReason = null,
        answer = null,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    /**
     * Transition to a new state with validation.
     * Throws InvalidStateTransitionException if transition is not valid.
     */
    fun transitionTo(newState: QuerySessionState) {
        if (!isValidTransition(state, newState)) {
            throw InvalidStateTransitionException(id, state, newState)
        }
        state = newState
        updatedAt = Clock.System.now()
    }

    /**
     * Mark session as failed.
     * This transition is valid from any state.
     */
    fun markFailed() {
        state = QuerySessionState.FAILED
        updatedAt = Clock.System.now()
    }

    /**
     * Set the finish reason if it hasn't been set already.
     * Idempotent: does nothing if a reason is already present.
     */
    fun finish(reason: FinishReason) {
        finishReason = reason
        updatedAt = Clock.System.now()
    }

    fun getDuration(): Duration {
        return Clock.System.now() - createdAt
    }

    /**
     * Complete the session with a final answer.
     * This sets the answer, finish reason, and transitions to FINISHED state.
     */
    fun completeWithAnswer(answer: String, finishReason: FinishReason) {
        this.answer = answer
        finish(finishReason)
        transitionTo(QuerySessionState.FINISHED)
    }

    /**
     * Transition to LINK_TRAVERSAL state (from EXPANDING_QUERY).
     */
    fun startLinkTraversal() {
        transitionTo(QuerySessionState.LINK_TRAVERSAL)
    }

    companion object {
        /**
         * Validate if a state transition is allowed.
         */
        private fun isValidTransition(from: QuerySessionState, to: QuerySessionState): Boolean {
            // FAILED can be reached from any state
            if (to == QuerySessionState.FAILED) return true

            // Same state is always valid (no-op)
            if (from == to) return true

            return when (from) {
                QuerySessionState.EXPANDING_QUERY ->
                    to == QuerySessionState.LINK_TRAVERSAL

                QuerySessionState.LINK_TRAVERSAL ->
                    to == QuerySessionState.FINISHED

                QuerySessionState.FINISHED, QuerySessionState.FAILED ->
                    false // Terminal states - no transitions allowed
            }
        }
    }
}

/**
 * State machine for query session lifecycle.
 *
 * Valid transitions:
 * - EXPANDING_QUERY → LINK_TRAVERSAL
 * - LINK_TRAVERSAL → FINISHED
 * - Any state → FAILED
 */
enum class QuerySessionState {
    /** Query is being expanded into sub-queries */
    EXPANDING_QUERY,

    /** Link traversal and answer generation happening concurrently */
    LINK_TRAVERSAL,

    /** Session completed successfully */
    FINISHED,

    /** Session failed due to error */
    FAILED
}

/**
 * Reason why a session finished.
 */
enum class FinishReason {
    TIME_BUDGET_EXCEEDED,
    MAX_LINKS_BUDGET_EXCEEDED,
    ANSWER_COMPLETE,
    LINKS_EXHAUSTED,
    EXECUTION_TIME_EXCEEDED, // hard coded fallback timeout
}

/**
 * Exception thrown when an invalid state transition is attempted.
 */
class InvalidStateTransitionException(
    val sessionId: String,
    val fromState: QuerySessionState,
    val toState: QuerySessionState
) : IllegalStateException("Invalid state transition for session [$sessionId]: $fromState → $toState")

