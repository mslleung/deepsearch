package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.QuerySession
import io.deepsearch.domain.models.entities.FinishReason
import io.deepsearch.domain.models.valueobjects.ApiKeyId
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
    /** Create a new query session in LINK_TRAVERSAL state. */
    suspend fun createSession(query: String, url: String, apiKeyId: ApiKeyId): QuerySession

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

    /**
     * Complete the session when all links have been exhausted without completing the answer.
     */
    suspend fun completeSessionLinksExhausted(
        sessionId: String,
        answer: String
    )

    suspend fun hardTimeout(sessionId: String, error: String)

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

    override suspend fun createSession(query: String, url: String, apiKeyId: ApiKeyId): QuerySession {
        val sessionId = UUID.randomUUID().toString()
        val session = QuerySession(sessionId, query, url, apiKeyId)
        val saved = querySessionRepository.save(session)
        logger.info(
            "[{}] Session created: query='{}', url='{}', apiKeyId={}",
            sessionId,
            query,
            url,
            apiKeyId.value
        )
        return saved
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

    override suspend fun completeSessionLinksExhausted(sessionId: String, answer: String) {
        val session = getSessionOrThrow(sessionId)
        session.completeWithAnswer(answer, FinishReason.LINKS_EXHAUSTED)
        querySessionRepository.update(session)
        logger.info(
            "[{}] Session completed: {} chars, links exhausted",
            sessionId,
            answer.length
        )
    }

    override suspend fun hardTimeout(sessionId: String, error: String) {
        val session = getSessionOrThrow(sessionId)
        session.finish(FinishReason.EXECUTION_TIME_EXCEEDED)
        querySessionRepository.update(session)
        logger.error("[{}] Session time out: {} ", sessionId, error)
    }

    override suspend fun getSession(sessionId: String): QuerySession {
        return getSessionOrThrow(sessionId)
    }

    private suspend fun getSessionOrThrow(sessionId: String): QuerySession {
        return querySessionRepository.findById(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")
    }
}


