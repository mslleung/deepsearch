package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.UncachedUrlAccess
import io.deepsearch.domain.models.valueobjects.UrlAccess

/**
 * Repository interface for UrlAccess aggregate root.
 * Provides CRUD operations and query methods for URL accesses.
 */
interface IUrlAccessRepository {
    /**
     * Save a URL access record for a query session.
     */
    suspend fun save(urlAccess: UrlAccess, querySessionId: QuerySessionId): UrlAccess

    /**
     * Find all URL accesses for a given query session.
     */
    suspend fun findByQuerySessionId(querySessionId: QuerySessionId): List<UrlAccess>

    /**
     * Find all cached URL accesses for a given query session.
     */
    suspend fun findCachedByQuerySessionId(querySessionId: QuerySessionId): List<CachedUrlAccess>

    /**
     * Find all uncached URL accesses for a given query session.
     */
    suspend fun findUncachedByQuerySessionId(querySessionId: QuerySessionId): List<UncachedUrlAccess>

    /**
     * Find all failed URL accesses for a given query session.
     */
    suspend fun findFailedByQuerySessionId(querySessionId: QuerySessionId): List<FailedUrlAccess>

    /**
     * Count total URL accesses for a given query session.
     */
    suspend fun countByQuerySessionId(querySessionId: QuerySessionId): Int

    /**
     * Check if a URL has been accessed in a given query session.
     */
    suspend fun existsByQuerySessionIdAndUrl(querySessionId: QuerySessionId, url: String): Boolean
    
    /**
     * Mark URLs as used in answer for a given query session.
     */
    suspend fun markUrlsAsUsedInAnswer(querySessionId: QuerySessionId, urls: List<String>): Int
}

