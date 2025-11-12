package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.ApiKeyId
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
    val apiKeyId: ApiKeyId,
    var searchBudget: SearchBudget,
    var finishReason: FinishReason?,
    var answer: String?,
    val createdAt: Instant,
    var updatedAt: Instant,
    var version: Long = 0
) {

    constructor(id: String, query: String, url: String, apiKeyId: ApiKeyId) : this(
        id = id,
        query = query,
        url = url,
        apiKeyId = apiKeyId,
        searchBudget = SearchBudget(),
        finishReason = null,
        answer = null,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

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
    }
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
