package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.domain.models.valueobjects.SearchMode
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
    val id: QuerySessionId,
    val query: String,
    val url: String,
    val apiKeyId: ApiKeyId,
    val searchMode: SearchMode,
    var searchBudget: SearchBudget,
    var finishReason: FinishReason?,
    var answer: String?,
    var answerFound: Boolean, // Whether a meaningful answer was found
    var imageIds: List<String> = emptyList(), // Image IDs referenced in the answer (format: "img-xxx")
    var durationMs: Long?,
    val createdAt: Instant,
    var updatedAt: Instant,
    var version: Long = 0
) {

    constructor(
        id: QuerySessionId,
        query: String,
        url: String,
        apiKeyId: ApiKeyId,
        searchMode: SearchMode,
        searchBudget: SearchBudget = SearchBudget()
    ) : this(
        id = id,
        query = query,
        url = url,
        apiKeyId = apiKeyId,
        searchMode = searchMode,
        searchBudget = searchBudget,
        finishReason = null,
        answer = null,
        answerFound = false,
        imageIds = emptyList(),
        durationMs = null,
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
        durationMs = (updatedAt - createdAt).inWholeMilliseconds
    }

    fun getDuration(): Duration {
        return Clock.System.now() - createdAt
    }

    /**
     * Complete the session with a final answer.
     * This sets the answer, finish reason, answerFound flag, image IDs, and transitions to FINISHED state.
     * @param answer The generated answer text
     * @param finishReason The reason why the session finished
     * @param answerFound Whether a meaningful answer was found (true) or not (false)
     * @param imageIds List of image IDs referenced in the answer
     */
    fun completeWithAnswer(answer: String, finishReason: FinishReason, answerFound: Boolean, imageIds: List<String> = emptyList()) {
        this.answer = answer
        this.answerFound = answerFound
        this.imageIds = imageIds
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
    PREVIEW_ANSWER_COMPLETE, // Preview path produced a confident answer
    LINKS_EXHAUSTED,
    EXECUTION_TIME_EXCEEDED, // hard coded fallback timeout
}
