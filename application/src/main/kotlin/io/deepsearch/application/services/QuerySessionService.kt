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
import java.net.URI
import kotlin.uuid.Uuid
import kotlin.time.Duration.Companion.milliseconds

/**
 * Data class to encapsulate query session detail with its related data.
 */
data class QuerySessionDetail(
    val session: QuerySession,
    val urlAccesses: List<UrlAccess>,
    val cachedWebpages: List<WebpageMarkdown>,
    val imageIds: List<String> = emptyList(),
    val fileSearchCitations: List<FileCitation> = emptyList()
)

/**
 * Analytics data for query sessions.
 */
data class QuerySessionAnalytics(
    val totalSessions: Int,
    val avgLiveSearchTimeMs: Long?,
    val avgStaticSearchTimeMs: Long?,
    val successRate: Double,           // % with ANSWER_COMPLETE finish reason
    val answerFoundRate: Double,       // % where answerFound = true
    val avgUrlsPerSession: Double,
    val domainStats: List<DomainStat>
)

/**
 * Statistics for a specific domain.
 */
data class DomainStat(
    val domain: String,
    val sessionCount: Int,
    val avgSearchTimeMs: Long?,
    val successRate: Double,
    val answerFoundRate: Double
)

/**
 * Application-level coordinator for `QuerySession` persistence and lifecycle orchestration.
 * Business rules live inside the `QuerySession` entity.
 */
interface IQuerySessionService {
    /** 
     * Create a new query session with the specified search mode and budget.
     * @param previousSessionId Optional ID of the immediate prior session being continued (for session continuation).
     * @param rootSessionId Optional ID of the first session in the continuation chain (for O(1) history loading).
     */
    suspend fun createSession(
        query: String,
        url: String,
        apiKeyId: ApiKeyId,
        searchMode: SearchMode,
        searchBudget: SearchBudget = SearchBudget(),
        previousSessionId: QuerySessionId? = null,
        rootSessionId: QuerySessionId? = null
    ): QuerySession

    /**
     * Complete the session with a final answer.
     * This sets the answer, finish reason, answerFound flag, image IDs, and transitions to FINISHED state.
     */
    suspend fun completeSessionAnswerComplete(
        sessionId: QuerySessionId,
        answer: String,
        answerFound: Boolean,
        imageIds: List<String> = emptyList()
    )

    /**
     * Complete the session with a preview answer (from the fast preview path).
     * Preview answers use simpler text extraction without full LLM processing.
     */
    suspend fun completeSessionPreviewAnswerComplete(
        sessionId: QuerySessionId,
        answer: String,
        answerFound: Boolean
    )

    /**
     * Check if the search budget has been exceeded.
     * Uses the budget stored in the session.
     * Returns true if budget was exceeded, false otherwise.
     */
    suspend fun isBudgetExceeded(sessionId: QuerySessionId): Boolean

    /**
     * Mark the session as having exceeded budget with appropriate finish reason.
     * Uses the budget stored in the session to determine which limit was exceeded.
     */
    suspend fun completeSessionBudgetExceeded(
        sessionId: QuerySessionId,
        answer: String,
        answerFound: Boolean,
        imageIds: List<String> = emptyList()
    )

    /**
     * Complete the session when all links have been exhausted without completing the answer.
     */
    suspend fun completeSessionLinksExhausted(
        sessionId: QuerySessionId,
        answer: String,
        answerFound: Boolean,
        imageIds: List<String> = emptyList()
    )

    /**
     * Complete the session due to an unrecoverable error.
     * This records the error message and sets the finish reason to ERROR.
     */
    suspend fun completeSessionWithError(
        sessionId: QuerySessionId,
        errorMessage: String
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
    
    /**
     * Get query sessions with search, filtering, and sorting.
     */
    suspend fun getSessionsWithFilters(
        userId: UserId,
        search: String?,
        domain: String?,
        status: String?,
        sortBy: String,
        sortOrder: String,
        offset: Int,
        limit: Int
    ): List<QuerySession>
    
    /**
     * Count sessions with filters (for pagination).
     */
    suspend fun countSessionsWithFilters(
        userId: UserId,
        search: String?,
        domain: String?,
        status: String?
    ): Long
    
    /**
     * Get analytics for a user's query sessions.
     */
    suspend fun getAnalytics(userId: UserId): QuerySessionAnalytics
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

    override suspend fun createSession(
        query: String,
        url: String,
        apiKeyId: ApiKeyId,
        searchMode: SearchMode,
        searchBudget: SearchBudget,
        previousSessionId: QuerySessionId?,
        rootSessionId: QuerySessionId?
    ): QuerySession {
        val sessionId = QuerySessionId(Uuid.random().toString())
        val session = QuerySession(sessionId, query, url, apiKeyId, searchMode, searchBudget, previousSessionId, rootSessionId)
        val saved = querySessionRepository.save(session)
        logger.info(
            "[{}] Session created: query='{}', url='{}', apiKeyId={}, mode={}, budget={}, previousSession={}, rootSession={}",
            sessionId.value,
            query,
            url,
            apiKeyId.value,
            searchMode.name,
            searchBudget,
            previousSessionId?.value,
            rootSessionId?.value
        )
        return saved
    }

    override suspend fun completeSessionAnswerComplete(
        sessionId: QuerySessionId,
        answer: String,
        answerFound: Boolean,
        imageIds: List<String>
    ) {
        val session = getSessionOrThrow(sessionId)
        session.completeWithAnswer(answer, FinishReason.ANSWER_COMPLETE, answerFound, imageIds)
        querySessionRepository.update(session)
        logger.info(
            "[{}] Session completed: {} chars, {} images, answerFound={}, answer complete",
            sessionId.value,
            answer.length,
            imageIds.size,
            answerFound
        )
    }

    override suspend fun completeSessionPreviewAnswerComplete(
        sessionId: QuerySessionId,
        answer: String,
        answerFound: Boolean
    ) {
        val session = getSessionOrThrow(sessionId)
        session.completeWithAnswer(answer, FinishReason.PREVIEW_ANSWER_COMPLETE, answerFound, emptyList())
        querySessionRepository.update(session)
        logger.info(
            "[{}] Session completed with preview answer: {} chars, answerFound={}",
            sessionId.value,
            answer.length,
            answerFound
        )
    }

    override suspend fun isBudgetExceeded(sessionId: QuerySessionId): Boolean {
        val session = getSessionOrThrow(sessionId)
        val budget = session.searchBudget

        // Check time budget in domain entity
        val isTimeBudgetExceeded = session.getDuration() > budget.timeLimitMs.milliseconds
        val isMaxLinksBudgetExceeded = urlAccessService.checkMaxLinkBudget(sessionId, budget.maxLinks)

        return isTimeBudgetExceeded || isMaxLinksBudgetExceeded
    }

    override suspend fun completeSessionBudgetExceeded(
        sessionId: QuerySessionId, 
        answer: String, 
        answerFound: Boolean,
        imageIds: List<String>
    ) {
        val session = getSessionOrThrow(sessionId)
        val budget = session.searchBudget

        // Determine which budget was exceeded
        val isTimeBudgetExceeded = session.getDuration() > budget.timeLimitMs.milliseconds
        val isMaxLinksBudgetExceeded = urlAccessService.checkMaxLinkBudget(sessionId, budget.maxLinks)

        if (isTimeBudgetExceeded) {
            val session = getSessionOrThrow(sessionId)
            session.completeWithAnswer(answer, FinishReason.TIME_BUDGET_EXCEEDED, answerFound, imageIds)
            querySessionRepository.update(session)
            logger.info(
                "[{}] Session completed: {} chars, {} images, answerFound={}, time budget exceeded",
                sessionId.value,
                answer.length,
                imageIds.size,
                answerFound
            )
        } else if (isMaxLinksBudgetExceeded) {
            val session = getSessionOrThrow(sessionId)
            session.completeWithAnswer(answer, FinishReason.MAX_LINKS_BUDGET_EXCEEDED, answerFound, imageIds)
            querySessionRepository.update(session)
            logger.info(
                "[{}] Session completed: {} chars, {} images, answerFound={}, max links budget exceeded",
                sessionId.value,
                answer.length,
                imageIds.size,
                answerFound
            )
        }
    }

    override suspend fun completeSessionLinksExhausted(
        sessionId: QuerySessionId, 
        answer: String,
        answerFound: Boolean,
        imageIds: List<String>
    ) {
        val session = getSessionOrThrow(sessionId)
        session.completeWithAnswer(answer, FinishReason.LINKS_EXHAUSTED, answerFound, imageIds)
        querySessionRepository.update(session)
        logger.info(
            "[{}] Session completed: {} chars, {} images, answerFound={}, links exhausted",
            sessionId.value,
            answer.length,
            imageIds.size,
            answerFound
        )
    }

    override suspend fun completeSessionWithError(
        sessionId: QuerySessionId,
        errorMessage: String
    ) {
        val session = getSessionOrThrow(sessionId)
        session.finish(FinishReason.ERROR)
        querySessionRepository.update(session)
        logger.error("[{}] Session completed with error: {}", sessionId.value, errorMessage)
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

        // Fetch cached webpages for URLs that were accessed (batch query for efficiency)
        val urls = urlAccesses.map { it.url }
        val cachedWebpages = webpageMarkdownRepository.findByUrls(urls)

        return QuerySessionDetail(
            session = session,
            urlAccesses = urlAccesses,
            cachedWebpages = cachedWebpages,
            imageIds = session.imageIds
        )
    }

    override suspend fun getSessionsWithFilters(
        userId: UserId,
        search: String?,
        domain: String?,
        status: String?,
        sortBy: String,
        sortOrder: String,
        offset: Int,
        limit: Int
    ): List<QuerySession> {
        return querySessionRepository.findByUserIdWithFilters(
            userId, search, domain, status, sortBy, sortOrder, offset, limit
        )
    }
    
    override suspend fun countSessionsWithFilters(
        userId: UserId,
        search: String?,
        domain: String?,
        status: String?
    ): Long {
        return querySessionRepository.countByUserIdWithFilters(userId, search, domain, status)
    }
    
    override suspend fun getAnalytics(userId: UserId): QuerySessionAnalytics {
        val allSessions = querySessionRepository.findAllByUserId(userId)
        
        if (allSessions.isEmpty()) {
            return QuerySessionAnalytics(
                totalSessions = 0,
                avgLiveSearchTimeMs = null,
                avgStaticSearchTimeMs = null,
                successRate = 0.0,
                answerFoundRate = 0.0,
                avgUrlsPerSession = 0.0,
                domainStats = emptyList()
            )
        }
        
        // Split by search mode
        val liveSessions = allSessions.filter { it.searchMode == SearchMode.LIVE_CRAWLING }
        val staticSessions = allSessions.filter { it.searchMode == SearchMode.CACHE_ONLY }
        
        // Calculate average durations
        val avgLiveSearchTimeMs = liveSessions
            .mapNotNull { it.durationMs }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toLong()
            
        val avgStaticSearchTimeMs = staticSessions
            .mapNotNull { it.durationMs }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toLong()
        
        // Success rate (ANSWER_COMPLETE)
        val completedSessions = allSessions.filter { it.finishReason != null }
        val successRate = if (completedSessions.isNotEmpty()) {
            completedSessions.count { it.finishReason == FinishReason.ANSWER_COMPLETE }.toDouble() / completedSessions.size
        } else 0.0
        
        // Answer found rate
        val sessionsWithAnswerFound = allSessions.filter { true }
        val answerFoundRate = if (sessionsWithAnswerFound.isNotEmpty()) {
            sessionsWithAnswerFound.count { it.answerFound }.toDouble() / sessionsWithAnswerFound.size
        } else 0.0
        
        // Average URLs per session
        val urlCounts = allSessions.map { session ->
            urlAccessService.getUrlAccessesBySession(session.id).size
        }
        val avgUrlsPerSession = if (urlCounts.isNotEmpty()) urlCounts.average() else 0.0
        
        // Group by domain
        val sessionsByDomain = allSessions.groupBy { extractDomain(it.url) }
        val domainStats = sessionsByDomain.map { (domain, sessions) ->
            val domainCompletedSessions = sessions.filter { it.finishReason != null }
            val domainSuccessRate = if (domainCompletedSessions.isNotEmpty()) {
                domainCompletedSessions.count { it.finishReason == FinishReason.ANSWER_COMPLETE }.toDouble() / domainCompletedSessions.size
            } else 0.0
            
            val domainSessionsWithAnswerFound = sessions.filter { true }
            val domainAnswerFoundRate = if (domainSessionsWithAnswerFound.isNotEmpty()) {
                domainSessionsWithAnswerFound.count { it.answerFound }.toDouble() / domainSessionsWithAnswerFound.size
            } else 0.0
            
            val avgSearchTimeMs = sessions
                .mapNotNull { it.durationMs }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toLong()
            
            DomainStat(
                domain = domain,
                sessionCount = sessions.size,
                avgSearchTimeMs = avgSearchTimeMs,
                successRate = domainSuccessRate,
                answerFoundRate = domainAnswerFoundRate
            )
        }.sortedByDescending { it.sessionCount }
        
        return QuerySessionAnalytics(
            totalSessions = allSessions.size,
            avgLiveSearchTimeMs = avgLiveSearchTimeMs,
            avgStaticSearchTimeMs = avgStaticSearchTimeMs,
            successRate = successRate,
            answerFoundRate = answerFoundRate,
            avgUrlsPerSession = avgUrlsPerSession,
            domainStats = domainStats
        )
    }
    
    private fun extractDomain(url: String): String {
        return try {
            val uri = URI(url)
            uri.host?.lowercase() ?: url
        } catch (e: Exception) {
            url
        }
    }

    private suspend fun getSessionOrThrow(sessionId: QuerySessionId): QuerySession {
        return querySessionRepository.findById(sessionId)
            ?: throw IllegalArgumentException("Session not found: ${sessionId.value}")
    }
}


