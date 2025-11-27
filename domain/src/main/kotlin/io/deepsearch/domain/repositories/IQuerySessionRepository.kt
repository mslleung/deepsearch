package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.QuerySession
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.UserId
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Repository for persisting and retrieving QuerySession entities.
 */
@OptIn(ExperimentalTime::class)
interface IQuerySessionRepository {
    /**
     * Save a new query session.
     */
    suspend fun save(session: QuerySession): QuerySession
    
    /**
     * Find a query session by ID.
     */
    suspend fun findById(id: QuerySessionId): QuerySession?
    
    /**
     * Update an existing query session.
     */
    suspend fun update(session: QuerySession): QuerySession
    
    /**
     * Count query sessions created since a given instant for rate limiting.
     */
    suspend fun countSessionsSince(apiKeyId: ApiKeyId, since: Instant): Long
    
    /**
     * Find query sessions by API key within a date range for usage stats.
     */
    suspend fun findByApiKeyIdAndDateRange(apiKeyId: ApiKeyId, start: Instant, end: Instant): List<QuerySession>
    
    /**
     * Find query sessions by user ID within a date range for usage stats.
     * This requires joining with the api_keys table.
     */
    suspend fun findByUserIdAndDateRange(userId: UserId, start: Instant, end: Instant): List<QuerySession>
    
    /**
     * Find query sessions by user ID with pagination.
     * Ordered by creation date descending (newest first).
     */
    suspend fun findByUserIdPaginated(userId: UserId, offset: Int, limit: Int): List<QuerySession>
    
    /**
     * Count total query sessions for a user.
     */
    suspend fun countByUserId(userId: UserId): Long
}

