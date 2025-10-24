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
     * Complete the session with a final answer and sources.
     * This marks the answer as complete, sets the finish reason, and transitions to FINISHED state.
     */
    suspend fun completeSessionWithAnswer(
        sessionId: String, 
        answer: String, 
        sources: List<String>, 
        finishReason: FinishReason
    )

    /**
     * Check if the search budget has been exceeded and mark the session accordingly.
     * Returns true if budget was exceeded (and finish reason was set), false otherwise.
     */
    suspend fun checkBudgetAndMarkIfExceeded(sessionId: String, budget: SearchBudget): Boolean

    /** Mark answer as complete and update answer content (without finishing the session). */
    suspend fun markAnswerComplete(sessionId: String, answer: String, sources: List<String>)

    /** Add a traversed URL to the session. */
    suspend fun addTraversedUrl(sessionId: String, url: String)

    /** Add multiple traversed URLs to the session. */
    suspend fun addTraversedUrls(sessionId: String, urls: Collection<String>)

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
    private val querySessionRepository: IQuerySessionRepository
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
        session.transitionTo(QuerySessionState.LINK_TRAVERSAL)
        querySessionRepository.update(session)
        logger.info("[{}] State transition: {} → {}", sessionId, session.state, QuerySessionState.LINK_TRAVERSAL)
    }

    override suspend fun completeSessionWithAnswer(
        sessionId: String,
        answer: String,
        sources: List<String>,
        finishReason: FinishReason
    ) {
        val session = getSessionOrThrow(sessionId)

        session.markAnswerComplete(answer, sources)
        session.setFinishReason(finishReason)
        session.transitionTo(QuerySessionState.FINISHED)

        querySessionRepository.update(session)
        logger.info(
            "[{}] Session completed: {} chars, {} sources, reason: {}",
            sessionId,
            answer.length,
            sources.size,
            finishReason
        )
    }

    override suspend fun checkBudgetAndMarkIfExceeded(
        sessionId: String,
        budget: io.deepsearch.domain.models.valueobjects.SearchBudget
    ): Boolean {
        val session = getSessionOrThrow(sessionId)
        val exceededReason = session.checkSearchBudget(budget)
        
        return if (exceededReason != null) {
            session.setFinishReason(exceededReason)
            querySessionRepository.update(session)
            logger.info("[{}] Budget exceeded: {}", sessionId, exceededReason)
            true
        } else {
            false
        }
    }

    override suspend fun markAnswerComplete(sessionId: String, answer: String, sources: List<String>) {
        val session = getSessionOrThrow(sessionId)
        session.markAnswerComplete(answer, sources)
        querySessionRepository.update(session)
        logger.info("[{}] Answer completed: {} chars, {} sources", sessionId, answer.length, sources.size)
    }

    override suspend fun addTraversedUrl(sessionId: String, url: String) {
        val session = getSessionOrThrow(sessionId)
        session.addTraversedUrl(url)
        querySessionRepository.update(session)
        logger.debug("[{}] Added traversed URL: {} (total: {})", sessionId, url, session.traversedUrls.size)
    }

    override suspend fun addTraversedUrls(sessionId: String, urls: Collection<String>) {
        if (urls.isEmpty()) return
        val session = getSessionOrThrow(sessionId)
        session.addTraversedUrls(urls)
        querySessionRepository.update(session)
        logger.debug("[{}] Added {} traversed URLs (total: {})", sessionId, urls.size, session.traversedUrls.size)
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


