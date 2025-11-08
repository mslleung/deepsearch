package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.QuerySession
import io.deepsearch.domain.models.entities.QuerySessionState
import io.deepsearch.domain.models.entities.FinishReason
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.domain.repositories.IQuerySessionRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Application-level coordinator for `QuerySession` persistence and lifecycle orchestration.
 * Business rules live inside the `QuerySession` entity.
 */
interface IQuerySessionService {
    /** Create a new query session in EXPANDING_QUERY state. */
    suspend fun createSession(query: String, url: String): QuerySession

    /** Transition session to LINK_TRAVERSAL (from EXPANDING_QUERY). */
    suspend fun transitionToLinkTraversal(sessionId: String)

    /**
     * Complete the session with a final answer.
     * This sets the answer, finish reason, and transitions to FINISHED state.
     */
    suspend fun completeSessionAnswerComplete(
        sessionId: String,
        answer: String
    )

    /**
     * Check if the search budget has been exceeded.
     * Returns true if budget was exceeded, false otherwise.
     */
    suspend fun isBudgetExceeded(sessionId: String, budget: SearchBudget): Boolean

    /**
     * Mark the session as having exceeded budget with appropriate finish reason.
     */
    suspend fun completeSessionBudgetExceeded(
        sessionId: String,
        answer: String,
        budget: SearchBudget
    )

    /** Transition session to FAILED with an error message (from any state). */
    suspend fun fail(sessionId: String, error: String)

    /** Get current state of session. */
    suspend fun getState(sessionId: String): QuerySessionState

    /** Get complete session. */
    suspend fun getSession(sessionId: String): QuerySession
}

/**
 * Application service that coordinates persistence for `QuerySession` while
 * delegating business rules to the `QuerySession` entity (rich domain model).
 */
class QuerySessionService(
    private val querySessionRepository: IQuerySessionRepository,
    private val urlAccessService: IUrlAccessService
) : IQuerySessionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun createSession(query: String, url: String): QuerySession {
        val sessionId = UUID.randomUUID().toString()
        val session = QuerySession(sessionId, query, url)
        val saved = querySessionRepository.save(session)
        logger.info(
            "[{}] Session created: query='{}', url='{}', state={}",
            sessionId,
            query,
            url,
            QuerySessionState.EXPANDING_QUERY
        )
        return saved
    }

    override suspend fun transitionToLinkTraversal(sessionId: String) {
        val session = getSessionOrThrow(sessionId)
        val previousState = session.state
        session.startLinkTraversal()
        querySessionRepository.update(session)
        logger.info("[{}] State transition: {} → {}", sessionId, previousState, session.state)
    }

    override suspend fun completeSessionAnswerComplete(
        sessionId: String,
        answer: String
    ) {
        val session = getSessionOrThrow(sessionId)
        session.completeWithAnswer(answer, FinishReason.ANSWER_COMPLETE)
        querySessionRepository.update(session)
        logger.info(
            "[{}] Session completed: {} chars, answer complete",
            sessionId,
            answer.length
        )
    }

    override suspend fun isBudgetExceeded(sessionId: String, budget: SearchBudget): Boolean {
        val session = getSessionOrThrow(sessionId)

        // Check time budget in domain entity
        val isTimeBudgetExceeded = session.getDuration() > budget.timeLimitMs.milliseconds
        val isMaxLinksBudgetExceeded = urlAccessService.checkMaxLinkBudget(sessionId, budget.maxLinks)

        return isTimeBudgetExceeded || isMaxLinksBudgetExceeded
    }

    override suspend fun completeSessionBudgetExceeded(sessionId: String, answer: String, budget: SearchBudget) {
        val session = getSessionOrThrow(sessionId)

        // Determine which budget was exceeded
        val isTimeBudgetExceeded = session.getDuration() > budget.timeLimitMs.milliseconds
        val isMaxLinksBudgetExceeded = urlAccessService.checkMaxLinkBudget(sessionId, budget.maxLinks)

        if (isTimeBudgetExceeded) {
            val session = getSessionOrThrow(sessionId)
            session.completeWithAnswer(answer, FinishReason.TIME_BUDGET_EXCEEDED)
            querySessionRepository.update(session)
            logger.info(
                "[{}] Session completed: {} chars, time budget exceeded",
                sessionId,
                answer.length
            )
        } else if (isMaxLinksBudgetExceeded) {
            val session = getSessionOrThrow(sessionId)
            session.completeWithAnswer(answer, FinishReason.MAX_LINKS_BUDGET_EXCEEDED)
            querySessionRepository.update(session)
            logger.info(
                "[{}] Session completed: {} chars, max links budget exceeded",
                sessionId,
                answer.length
            )
        }
    }

    override suspend fun fail(sessionId: String, error: String) {
        val session = getSessionOrThrow(sessionId)
        session.markFailed()
        querySessionRepository.update(session)
        logger.error("[{}] Session failed: {} (from state: {})", sessionId, error, session.state)
    }

    override suspend fun getState(sessionId: String): QuerySessionState {
        return getSessionOrThrow(sessionId).state
    }

    override suspend fun getSession(sessionId: String): QuerySession {
        return getSessionOrThrow(sessionId)
    }

    private suspend fun getSessionOrThrow(sessionId: String): QuerySession {
        return querySessionRepository.findById(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")
    }
}


