package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.*
import io.deepsearch.domain.repositories.IUrlAccessRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Application service for managing UrlAccess aggregate root.
 * Provides business logic for recording and querying URL accesses.
 * Supports both query sessions (QuerySessionId) and periodic index sessions (PeriodicIndexSessionId).
 */
interface IUrlAccessService {
    /** Record a URL access for a session. */
    suspend fun recordUrlAccess(sessionId: SessionId, urlAccess: UrlAccess)

    /** Check if a URL has been visited in a session. */
    suspend fun hasVisitedUrl(sessionId: SessionId, url: String): Boolean

    /** Get all URL accesses for a session. */
    suspend fun getUrlAccessesBySession(sessionId: SessionId): List<UrlAccess>

    /** Get all URL accesses for a session with pagination. */
    suspend fun getUrlAccessesBySession(sessionId: SessionId, page: Int, pageSize: Int): Pair<List<UrlAccess>, Int>

    /** Count total URL accesses for a session. */
    suspend fun countUrlAccessesBySession(sessionId: SessionId): Int

    /** Get all cached URL accesses for a session. */
    suspend fun getCachedUrls(sessionId: SessionId): List<CachedUrlAccess>

    /** Get all uncached URL accesses for a session. */
    suspend fun getUncachedUrls(sessionId: SessionId): List<UncachedUrlAccess>

    /** Get all failed URL accesses for a session. */
    suspend fun getFailedUrls(sessionId: SessionId): List<FailedUrlAccess>

    /** Check if the maximum link budget has been exceeded for a session. */
    suspend fun checkMaxLinkBudget(sessionId: SessionId, maxLinks: Int): Boolean
    
    /** Mark URLs as used in answer for a session. */
    suspend fun markUrlsAsUsedInAnswer(sessionId: SessionId, urls: List<String>): Int

    /** Get all URL accesses for a given base URL prefix with pagination. */
    suspend fun getUrlAccessesByBaseUrl(baseUrl: String, page: Int, pageSize: Int): Pair<List<UrlAccess>, Int>
}

/**
 * Implementation of UrlAccessService that coordinates UrlAccess persistence
 * and provides business logic for URL access tracking.
 */
class UrlAccessService(
    private val urlAccessRepository: IUrlAccessRepository
) : IUrlAccessService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun recordUrlAccess(sessionId: SessionId, urlAccess: UrlAccess) {
        urlAccessRepository.save(urlAccess, sessionId)
        logger.debug(
            "[{}] Recorded URL access: {} (type: {})",
            sessionId.value,
            urlAccess.url,
            urlAccess::class.simpleName
        )
    }

    override suspend fun hasVisitedUrl(sessionId: SessionId, url: String): Boolean {
        return urlAccessRepository.existsBySessionIdAndUrl(sessionId, url)
    }

    override suspend fun getUrlAccessesBySession(sessionId: SessionId): List<UrlAccess> {
        return urlAccessRepository.findBySessionId(sessionId)
    }

    override suspend fun getUrlAccessesBySession(sessionId: SessionId, page: Int, pageSize: Int): Pair<List<UrlAccess>, Int> {
        val offset = (page - 1) * pageSize
        val urls = urlAccessRepository.findBySessionId(sessionId, offset, pageSize)
        val total = urlAccessRepository.countBySessionId(sessionId)
        return Pair(urls, total)
    }

    override suspend fun countUrlAccessesBySession(sessionId: SessionId): Int {
        return urlAccessRepository.countBySessionId(sessionId)
    }

    override suspend fun getCachedUrls(sessionId: SessionId): List<CachedUrlAccess> {
        return urlAccessRepository.findCachedBySessionId(sessionId)
    }

    override suspend fun getUncachedUrls(sessionId: SessionId): List<UncachedUrlAccess> {
        return urlAccessRepository.findUncachedBySessionId(sessionId)
    }

    override suspend fun getFailedUrls(sessionId: SessionId): List<FailedUrlAccess> {
        return urlAccessRepository.findFailedBySessionId(sessionId)
    }

    override suspend fun checkMaxLinkBudget(sessionId: SessionId, maxLinks: Int): Boolean {
        val urlAccessCount = countUrlAccessesBySession(sessionId)
        return urlAccessCount >= maxLinks
    }
    
    override suspend fun markUrlsAsUsedInAnswer(sessionId: SessionId, urls: List<String>): Int {
        logger.debug("[{}] Marking {} URLs as used in answer", sessionId.value, urls.size)
        return urlAccessRepository.markUrlsAsUsedInAnswer(sessionId, urls)
    }

    override suspend fun getUrlAccessesByBaseUrl(baseUrl: String, page: Int, pageSize: Int): Pair<List<UrlAccess>, Int> {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val offset = (page - 1) * pageSize
        val urls = urlAccessRepository.findByUrlPrefix(normalizedBaseUrl, offset, pageSize)
        val total = urlAccessRepository.countByUrlPrefix(normalizedBaseUrl)
        return Pair(urls, total)
    }
}

