package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.*
import io.deepsearch.domain.repositories.IUrlAccessRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Application service for managing UrlAccess aggregate root.
 * Provides business logic for recording and querying URL accesses.
 */
interface IUrlAccessService {
    /** Record a URL access for a query session. */
    suspend fun recordUrlAccess(querySessionId: String, urlAccess: UrlAccess)

    /** Check if a URL has been visited in a query session. */
    suspend fun hasVisitedUrl(querySessionId: String, url: String): Boolean

    /** Get all URL accesses for a query session. */
    suspend fun getUrlAccessesBySession(querySessionId: String): List<UrlAccess>

    /** Count total URL accesses for a query session. */
    suspend fun countUrlAccessesBySession(querySessionId: String): Int

    /** Get all cached URL accesses for a query session. */
    suspend fun getCachedUrls(querySessionId: String): List<CachedUrlAccess>

    /** Get all uncached URL accesses for a query session. */
    suspend fun getUncachedUrls(querySessionId: String): List<UncachedUrlAccess>

    /** Get all failed URL accesses for a query session. */
    suspend fun getFailedUrls(querySessionId: String): List<FailedUrlAccess>

    /** Check if the maximum link budget has been exceeded for a query session. */
    suspend fun checkMaxLinkBudget(querySessionId: String, maxLinks: Int): Boolean
    
    /** Mark URLs as used in answer for a query session. */
    suspend fun markUrlsAsUsedInAnswer(querySessionId: String, urls: List<String>): Int
}

/**
 * Implementation of UrlAccessService that coordinates UrlAccess persistence
 * and provides business logic for URL access tracking.
 */
class UrlAccessService(
    private val urlAccessRepository: IUrlAccessRepository
) : IUrlAccessService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun recordUrlAccess(querySessionId: String, urlAccess: UrlAccess) {
        urlAccessRepository.save(urlAccess, querySessionId)
        logger.debug(
            "[{}] Recorded URL access: {} (type: {})",
            querySessionId,
            urlAccess.url,
            urlAccess::class.simpleName
        )
    }

    override suspend fun hasVisitedUrl(querySessionId: String, url: String): Boolean {
        return urlAccessRepository.existsByQuerySessionIdAndUrl(querySessionId, url)
    }

    override suspend fun getUrlAccessesBySession(querySessionId: String): List<UrlAccess> {
        return urlAccessRepository.findByQuerySessionId(querySessionId)
    }

    override suspend fun countUrlAccessesBySession(querySessionId: String): Int {
        return urlAccessRepository.countByQuerySessionId(querySessionId)
    }

    override suspend fun getCachedUrls(querySessionId: String): List<CachedUrlAccess> {
        return urlAccessRepository.findCachedByQuerySessionId(querySessionId)
    }

    override suspend fun getUncachedUrls(querySessionId: String): List<UncachedUrlAccess> {
        return urlAccessRepository.findUncachedByQuerySessionId(querySessionId)
    }

    override suspend fun getFailedUrls(querySessionId: String): List<FailedUrlAccess> {
        return urlAccessRepository.findFailedByQuerySessionId(querySessionId)
    }

    override suspend fun checkMaxLinkBudget(querySessionId: String, maxLinks: Int): Boolean {
        val urlAccessCount = countUrlAccessesBySession(querySessionId)
        return urlAccessCount >= maxLinks
    }
    
    override suspend fun markUrlsAsUsedInAnswer(querySessionId: String, urls: List<String>): Int {
        logger.debug("[{}] Marking {} URLs as used in answer", querySessionId, urls.size)
        return urlAccessRepository.markUrlsAsUsedInAnswer(querySessionId, urls)
    }
}

