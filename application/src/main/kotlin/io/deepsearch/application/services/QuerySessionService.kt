package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.QuerySession
import io.deepsearch.domain.models.entities.QuerySessionState
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

    /** Transition session to INITIAL_LINK_DISCOVERY (from EXPANDING_QUERY). */
    suspend fun transitionToInitialDiscovery(sessionId: String)

    /** Transition session to LINK_TRAVERSAL (from INITIAL_LINK_DISCOVERY). */
    suspend fun transitionToLinkTraversal(sessionId: String)

    /** Mark answer as complete and update answer content; state change handled separately. */
    suspend fun markAnswerComplete(sessionId: String, answer: String, sources: List<String>)

    /** Transition session to TRAILING_LINK_TRAVERSAL (from LINK_TRAVERSAL). */
    suspend fun transitionToTrailingTraversal(sessionId: String)

    /** Add a traversed URL to the session. */
    suspend fun addTraversedUrl(sessionId: String, url: String)

    /** Add multiple traversed URLs to the session. */
    suspend fun addTraversedUrls(sessionId: String, urls: Collection<String>)

    /** Transition session to FINISHED (from LINK_TRAVERSAL or TRAILING_LINK_TRAVERSAL). */
    suspend fun finish(sessionId: String)

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

    override suspend fun transitionToInitialDiscovery(sessionId: String) {
        val session = getSessionOrThrow(sessionId)
        val updated = session.transitionTo(QuerySessionState.INITIAL_LINK_DISCOVERY)
        querySessionRepository.update(updated)
        logger.info("[{}] State transition: {} → {}", sessionId, session.state, QuerySessionState.INITIAL_LINK_DISCOVERY)
    }

    override suspend fun transitionToLinkTraversal(sessionId: String) {
        val session = getSessionOrThrow(sessionId)
        val updated = session.transitionTo(QuerySessionState.LINK_TRAVERSAL)
        querySessionRepository.update(updated)
        logger.info("[{}] State transition: {} → {}", sessionId, session.state, QuerySessionState.LINK_TRAVERSAL)
    }

    override suspend fun markAnswerComplete(sessionId: String, answer: String, sources: List<String>) {
        val session = getSessionOrThrow(sessionId)
        val updated = session.markAnswerComplete(answer, sources)
        querySessionRepository.update(updated)
        logger.info("[{}] Answer completed: {} chars, {} sources", sessionId, answer.length, sources.size)
    }

    override suspend fun transitionToTrailingTraversal(sessionId: String) {
        val session = getSessionOrThrow(sessionId)
        val updated = session.transitionTo(QuerySessionState.TRAILING_LINK_TRAVERSAL)
        querySessionRepository.update(updated)
        logger.info("[{}] State transition: {} → {}", sessionId, session.state, QuerySessionState.TRAILING_LINK_TRAVERSAL)
    }

    override suspend fun addTraversedUrl(sessionId: String, url: String) {
        val session = getSessionOrThrow(sessionId)
        val updated = session.addTraversedUrl(url)
        querySessionRepository.update(updated)
        logger.debug("[{}] Added traversed URL: {} (total: {})", sessionId, url, updated.traversedUrls.size)
    }

    override suspend fun addTraversedUrls(sessionId: String, urls: Collection<String>) {
        if (urls.isEmpty()) return
        val session = getSessionOrThrow(sessionId)
        val updated = session.addTraversedUrls(urls)
        querySessionRepository.update(updated)
        logger.debug("[{}] Added {} traversed URLs (total: {})", sessionId, urls.size, updated.traversedUrls.size)
    }

    override suspend fun finish(sessionId: String) {
        val session = getSessionOrThrow(sessionId)
        val updated = session.transitionTo(QuerySessionState.FINISHED)
        querySessionRepository.update(updated)
        logger.info("[{}] Session finished successfully (from state: {})", sessionId, session.state)
    }

    override suspend fun fail(sessionId: String, error: String) {
        val session = getSessionOrThrow(sessionId)
        val updated = session.markFailed()
        querySessionRepository.update(updated)
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


