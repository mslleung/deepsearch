package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.valueobjects.UrlAccess

/**
 * Repository interface for UrlAccess aggregate root.
 * Provides CRUD operations and query methods for URL accesses.
 */
interface IUrlAccessRepository {
    /**
     * Save a URL access record for a query session.
     */
    suspend fun save(urlAccess: UrlAccess, querySessionId: String): UrlAccess

    /**
     * Find all URL accesses for a given query session.
     */
    suspend fun findByQuerySessionId(querySessionId: String): List<UrlAccess>

    /**
     * Count total URL accesses for a given query session.
     */
    suspend fun countByQuerySessionId(querySessionId: String): Int

    /**
     * Check if a URL has been accessed in a given query session.
     */
    suspend fun existsByQuerySessionIdAndUrl(querySessionId: String, url: String): Boolean
}

