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
    
    /**
     * Find query sessions by user ID with search, filtering, and sorting.
     * @param userId The user ID to fetch sessions for
     * @param search Optional search term to filter by query, URL, or status
     * @param domain Optional domain to filter by (extracted from URL)
     * @param status Optional status to filter by
     * @param sortBy Field to sort by: "createdAt", "duration", "urlCount", "domain"
     * @param sortOrder Sort order: "asc" or "desc"
     * @param offset Pagination offset
     * @param limit Maximum number of results
     * @return List of matching query sessions
     */
    suspend fun findByUserIdWithFilters(
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
     * Count query sessions by user ID with search and filtering (for pagination).
     */
    suspend fun countByUserIdWithFilters(
        userId: UserId,
        search: String?,
        domain: String?,
        status: String?
    ): Long
    
    /**
     * Get all unique domains searched by a user.
     * @param userId The user ID
     * @return List of unique domains extracted from session URLs
     */
    suspend fun getDistinctDomainsByUserId(userId: UserId): List<String>
    
    /**
     * Get all sessions for a user (for analytics calculation).
     * @param userId The user ID
     * @return List of all query sessions for the user
     */
    suspend fun findAllByUserId(userId: UserId): List<QuerySession>
}

