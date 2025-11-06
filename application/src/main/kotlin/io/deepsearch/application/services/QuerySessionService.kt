package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.QuerySession
import io.deepsearch.domain.models.entities.QuerySessionState
import io.deepsearch.domain.models.entities.FinishReason
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.domain.repositories.IQuerySessionRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

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
    suspend fun completeSessionWithAnswer(
        sessionId: String, 
        answer: String, 
        finishReason: FinishReason
    )

    /**
     * Check if the search budget has been exceeded and mark the session accordingly.
     * Returns true if budget was exceeded (and finish reason was set), false otherwise.
     */
    suspend fun checkBudgetAndMarkIfExceeded(sessionId: String, budget: SearchBudget): Boolean

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
        logger.info("[{}] Session created: query='{}', url='{}', state={}", sessionId, query, url, QuerySessionState.EXPANDING_QUERY)
        return saved
    }

    override suspend fun transitionToLinkTraversal(sessionId: String) {
        val session = getSessionOrThrow(sessionId)
        val previousState = session.state
        session.startLinkTraversal()
        querySessionRepository.update(session)
        logger.info("[{}] State transition: {} → {}", sessionId, previousState, session.state)
    }

    override suspend fun completeSessionWithAnswer(
        sessionId: String,
        answer: String,
        finishReason: FinishReason
    ) {
        val session = getSessionOrThrow(sessionId)
        session.completeWithAnswer(answer, finishReason)
        querySessionRepository.update(session)
        logger.info(
            "[{}] Session completed: {} chars, reason: {}",
            sessionId,
            answer.length,
            finishReason
        )
    }

    override suspend fun checkBudgetAndMarkIfExceeded(
        sessionId: String,
        budget: SearchBudget
    ): Boolean {
        val session = getSessionOrThrow(sessionId)
        
        // Query URL access count from UrlAccessService
        val urlAccessCount = urlAccessService.countUrlAccessesBySession(sessionId)
        val exceeded = session.checkAndApplyBudgetExceeded(urlAccessCount, budget)
        
        if (exceeded) {
            querySessionRepository.update(session)
            logger.info("[{}] Budget exceeded: {}", sessionId, session.finishReason)
        }
        
        return exceeded
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


