package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.SearchBudget

/**
 * Represents a search query session with state tracking for coordinating
 * background link traversal and foreground answer generation.
 *
 * This is a rich domain model that encapsulates business logic for state transitions
 * and state management, following DDD principles.
 */
class QuerySession(
    val id: String,
    val query: String,
    val url: String,
    var state: QuerySessionState,
    var searchBudget: SearchBudget,
    var finishReason: FinishReason?,
    var answerComplete: Boolean,
    var answer: String?,
    val traversedUrls: MutableSet<String>,
    var sourcesDiscovered: MutableList<String>,
    val createdAtEpochMs: Long,
    var updatedAtEpochMs: Long,
) {

    constructor(id: String, query: String, url: String) : this(
        id = id,
        query = query,
        url = url,
        state = QuerySessionState.EXPANDING_QUERY,
        searchBudget = SearchBudget(),
        finishReason = null,
        answerComplete = false,
        answer = null,
        traversedUrls = mutableSetOf(),
        sourcesDiscovered = mutableListOf(),
        createdAtEpochMs = System.currentTimeMillis(),
        updatedAtEpochMs = System.currentTimeMillis(),
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
        updatedAtEpochMs = System.currentTimeMillis()
    }

    /**
     * Mark answer as complete with the generated answer and sources.
     * Does not change state - state transition should be done separately.
     */
    fun markAnswerComplete(answer: String, sources: List<String>) {
        this.answerComplete = true
        this.answer = answer
        sourcesDiscovered.clear()
        sourcesDiscovered.addAll(sources)
        updatedAtEpochMs = System.currentTimeMillis()
    }

    /**
     * Add a single traversed URL to the session.
     */
    fun addTraversedUrl(url: String) {
        traversedUrls.add(url)
        updatedAtEpochMs = System.currentTimeMillis()
    }

    /**
     * Add multiple traversed URLs to the session.
     */
    fun addTraversedUrls(urls: Collection<String>) {
        if (urls.isEmpty()) return
        traversedUrls.addAll(urls)
        updatedAtEpochMs = System.currentTimeMillis()
    }

    /**
     * Mark session as failed.
     * This transition is valid from any state.
     */
    fun markFailed() {
        state = QuerySessionState.FAILED
        updatedAtEpochMs = System.currentTimeMillis()
    }

    /**
     * Set the finish reason if it hasn't been set already.
     * Idempotent: does nothing if a reason is already present.
     */
    fun setFinishReason(reason: FinishReason) {
        finishReason = reason
        updatedAtEpochMs = System.currentTimeMillis()
    }

    /**
     * Check whether the search budget has been exceeded at this moment.
     * Returns the corresponding FinishReason if exceeded, or null otherwise.
     */
    fun checkSearchBudget(budget: SearchBudget = searchBudget): FinishReason? {
        val elapsedMs = System.currentTimeMillis() - createdAtEpochMs
        return when {
            elapsedMs >= budget.timeLimitMs -> FinishReason.TIME_EXCEEDED
            traversedUrls.size >= budget.maxLinks -> FinishReason.MAX_LINKS_EXCEEDED
            else -> null
        }
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
    TIME_EXCEEDED,
    MAX_LINKS_EXCEEDED,
    ANSWER_COMPLETE,
    LINKS_EXHAUSTED
}

/**
 * Exception thrown when an invalid state transition is attempted.
 */
class InvalidStateTransitionException(
    val sessionId: String,
    val fromState: QuerySessionState,
    val toState: QuerySessionState
) : IllegalStateException("Invalid state transition for session [$sessionId]: $fromState → $toState")

