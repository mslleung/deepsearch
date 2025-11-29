package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.QuerySession
import io.deepsearch.domain.models.entities.FinishReason
import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.domain.models.valueobjects.SearchMode
import io.deepsearch.domain.models.valueobjects.UrlAccess
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IQuerySessionRepository
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Data class to encapsulate query session detail with its related data.
 */
data class QuerySessionDetail(
    val session: QuerySession,
    val urlAccesses: List<UrlAccess>,
    val cachedWebpages: List<WebpageMarkdown>
)

/**
 * Application-level coordinator for `QuerySession` persistence and lifecycle orchestration.
 * Business rules live inside the `QuerySession` entity.
 */
interface IQuerySessionService {
    /** Create a new query session with the specified search mode. */
    suspend fun createSession(query: String, url: String, apiKeyId: ApiKeyId, searchMode: SearchMode): QuerySession

    /**
     * Complete the session with a final answer.
     * This sets the answer, finish reason, and transitions to FINISHED state.
     */
    suspend fun completeSessionAnswerComplete(
        sessionId: QuerySessionId,
        answer: String
    )

    /**
     * Check if the search budget has been exceeded.
     * Returns true if budget was exceeded, false otherwise.
     */
    suspend fun isBudgetExceeded(sessionId: QuerySessionId, budget: SearchBudget): Boolean

    /**
     * Mark the session as having exceeded budget with appropriate finish reason.
     */
    suspend fun completeSessionBudgetExceeded(
        sessionId: QuerySessionId,
        answer: String,
        budget: SearchBudget
    )

    /**
     * Complete the session when all links have been exhausted without completing the answer.
     */
    suspend fun completeSessionLinksExhausted(
        sessionId: QuerySessionId,
        answer: String
    )

    suspend fun hardTimeout(sessionId: QuerySessionId, error: String)

    /** Get complete session. */
    suspend fun getSession(sessionId: QuerySessionId): QuerySession

    /**
     * Get paginated query sessions for a user.
     * @param userId The user ID to fetch sessions for
     * @param offset The offset for pagination
     * @param limit The maximum number of sessions to return
     * @return List of query sessions
     */
    suspend fun getSessionsByUserId(userId: UserId, offset: Int, limit: Int): List<QuerySession>

    /**
     * Count total query sessions for a user.
     * @param userId The user ID to count sessions for
     * @return Total number of sessions
     */
    suspend fun countSessionsByUserId(userId: UserId): Long

    /**
     * Get query session detail with URL accesses and cached webpages.
     * @param sessionId The session ID
     * @param userId The user ID (for authorization check)
     * @return QuerySessionDetail containing session, URL accesses, and cached webpages
     * @throws IllegalArgumentException if session not found
     * @throws IllegalAccessException if user doesn't have access to the session
     */
    suspend fun getSessionDetail(sessionId: QuerySessionId, userId: UserId): QuerySessionDetail

    /**
     * Get query session detail with URL accesses and cached webpages.
     * Internal use only - no user authorization check.
     * @param sessionId The session ID
     * @return QuerySessionDetail containing session, URL accesses, and cached webpages
     * @throws IllegalArgumentException if session not found
     */
    suspend fun getSessionDetailInternal(sessionId: QuerySessionId): QuerySessionDetail
}

/**
 * Application service that coordinates persistence for `QuerySession` while
 * delegating business rules to the `QuerySession` entity (rich domain model).
 */
class QuerySessionService(
    private val querySessionRepository: IQuerySessionRepository,
    private val urlAccessService: IUrlAccessService,
    private val webpageMarkdownRepository: IWebpageMarkdownRepository
) : IQuerySessionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun createSession(query: String, url: String, apiKeyId: ApiKeyId, searchMode: SearchMode): QuerySession {
        val sessionId = QuerySessionId(UUID.randomUUID().toString())
        val session = QuerySession(sessionId, query, url, apiKeyId, searchMode)
        val saved = querySessionRepository.save(session)
        logger.info(
            "[{}] Session created: query='{}', url='{}', apiKeyId={}, mode={}",
            sessionId.value,
            query,
            url,
            apiKeyId.value,
            searchMode.name
        )
        return saved
    }

    override suspend fun completeSessionAnswerComplete(
        sessionId: QuerySessionId,
        answer: String
    ) {
        val session = getSessionOrThrow(sessionId)
        session.completeWithAnswer(answer, FinishReason.ANSWER_COMPLETE)
        querySessionRepository.update(session)
        logger.info(
            "[{}] Session completed: {} chars, answer complete",
            sessionId.value,
            answer.length
        )
    }

    override suspend fun isBudgetExceeded(sessionId: QuerySessionId, budget: SearchBudget): Boolean {
        val session = getSessionOrThrow(sessionId)

        // Check time budget in domain entity
        val isTimeBudgetExceeded = session.getDuration() > budget.timeLimitMs.milliseconds
        val isMaxLinksBudgetExceeded = urlAccessService.checkMaxLinkBudget(sessionId, budget.maxLinks)

        return isTimeBudgetExceeded || isMaxLinksBudgetExceeded
    }

    override suspend fun completeSessionBudgetExceeded(
        sessionId: QuerySessionId, 
        answer: String, 
        budget: SearchBudget
    ) {
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
                sessionId.value,
                answer.length
            )
        } else if (isMaxLinksBudgetExceeded) {
            val session = getSessionOrThrow(sessionId)
            session.completeWithAnswer(answer, FinishReason.MAX_LINKS_BUDGET_EXCEEDED)
            querySessionRepository.update(session)
            logger.info(
                "[{}] Session completed: {} chars, max links budget exceeded",
                sessionId.value,
                answer.length
            )
        }
    }

    override suspend fun completeSessionLinksExhausted(
        sessionId: QuerySessionId, 
        answer: String
    ) {
        val session = getSessionOrThrow(sessionId)
        session.completeWithAnswer(answer, FinishReason.LINKS_EXHAUSTED)
        querySessionRepository.update(session)
        logger.info(
            "[{}] Session completed: {} chars, links exhausted",
            sessionId.value,
            answer.length
        )
    }

    override suspend fun hardTimeout(sessionId: QuerySessionId, error: String) {
        val session = getSessionOrThrow(sessionId)
        session.finish(FinishReason.EXECUTION_TIME_EXCEEDED)
        querySessionRepository.update(session)
        logger.error("[{}] Session time out: {} ", sessionId.value, error)
    }

    override suspend fun getSession(sessionId: QuerySessionId): QuerySession {
        return getSessionOrThrow(sessionId)
    }

    override suspend fun getSessionsByUserId(userId: UserId, offset: Int, limit: Int): List<QuerySession> {
        return querySessionRepository.findByUserIdPaginated(userId, offset, limit)
    }

    override suspend fun countSessionsByUserId(userId: UserId): Long {
        return querySessionRepository.countByUserId(userId)
    }

    override suspend fun getSessionDetail(sessionId: QuerySessionId, userId: UserId): QuerySessionDetail {
        // Verify the session belongs to the user
        val userSessions = querySessionRepository.findByUserIdPaginated(userId, 0, Int.MAX_VALUE)
        if (!userSessions.any { it.id == sessionId }) {
            throw IllegalAccessException("User $userId does not have access to session $sessionId")
        }

        return getSessionDetailInternal(sessionId)
    }

    override suspend fun getSessionDetailInternal(sessionId: QuerySessionId): QuerySessionDetail {
        // Get the session
        val session = getSessionOrThrow(sessionId)

        // Get URL accesses for the session
        val urlAccesses = urlAccessService.getUrlAccessesBySession(sessionId)

        // Fetch cached webpages for URLs that were accessed
        val urls = urlAccesses.map { it.url }
        val cachedWebpages = urls.mapNotNull { url ->
            webpageMarkdownRepository.findByUrl(url)
        }

        return QuerySessionDetail(
            session = session,
            urlAccesses = urlAccesses,
            cachedWebpages = cachedWebpages
        )
    }

    private suspend fun getSessionOrThrow(sessionId: QuerySessionId): QuerySession {
        return querySessionRepository.findById(sessionId)
            ?: throw IllegalArgumentException("Session not found: ${sessionId.value}")
    }
}


