package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.UncachedUrlAccess
import io.deepsearch.domain.models.valueobjects.UrlAccess

/**
 * Repository interface for UrlAccess aggregate root.
 * Provides CRUD operations and query methods for URL accesses.
 * Supports both query sessions (QuerySessionId) and periodic index sessions (PeriodicIndexSessionId).
 */
interface IUrlAccessRepository {
    /**
     * Save a URL access record for a session.
     */
    suspend fun save(urlAccess: UrlAccess, sessionId: SessionId): UrlAccess

    /**
     * Find all URL accesses for a given session.
     */
    suspend fun findBySessionId(sessionId: SessionId): List<UrlAccess>

    /**
     * Find all URL accesses for a given session with pagination.
     */
    suspend fun findBySessionId(sessionId: SessionId, offset: Int, limit: Int): List<UrlAccess>

    /**
     * Find all cached URL accesses for a given session.
     */
    suspend fun findCachedBySessionId(sessionId: SessionId): List<CachedUrlAccess>

    /**
     * Find all uncached URL accesses for a given session.
     */
    suspend fun findUncachedBySessionId(sessionId: SessionId): List<UncachedUrlAccess>

    /**
     * Find all failed URL accesses for a given session.
     */
    suspend fun findFailedBySessionId(sessionId: SessionId): List<FailedUrlAccess>

    /**
     * Count total URL accesses for a given session.
     */
    suspend fun countBySessionId(sessionId: SessionId): Int

    /**
     * Check if a URL has been accessed in a given session.
     */
    suspend fun existsBySessionIdAndUrl(sessionId: SessionId, url: String): Boolean
    
    /**
     * Mark URLs as used in answer for a given session.
     */
    suspend fun markUrlsAsUsedInAnswer(sessionId: SessionId, urls: List<String>): Int

    /**
     * Find all URL accesses for a given base URL prefix with pagination.
     * This is useful for finding all indexed URLs for a domain.
     */
    suspend fun findByUrlPrefix(urlPrefix: String, offset: Int, limit: Int): List<UrlAccess>

    /**
     * Count all URL accesses for a given base URL prefix.
     */
    suspend fun countByUrlPrefix(urlPrefix: String): Int
}

