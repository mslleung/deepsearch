package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Result of cache lookup operation.
 */
sealed class CachedWebpageResult {
    data class Hit(val webpageMarkdown: WebpageMarkdown) : CachedWebpageResult()
    data object Miss : CachedWebpageResult()
    data class Expired(val webpageMarkdown: WebpageMarkdown) : CachedWebpageResult()
}

interface IWebpageCacheService {
    /**
     * Get cached markdown for a URL, checking TTL expiration.
     * @param url The normalized URL to look up
     * @return CachedWebpageResult indicating hit, miss, or expired entry
     */
    suspend fun getCachedMarkdown(url: String): CachedWebpageResult

    /**
     * Cache webpage markdown and metadata.
     * @param url The normalized URL
     * @param markdown The extracted markdown content (null if extraction failed)
     * @param html The original HTML (null for non-HTML content)
     * @param httpStatus HTTP status code
     * @param httpReason HTTP reason phrase
     * @param mimeType Content MIME type
     */
    suspend fun cacheWebpage(
        url: String,
        markdown: String?,
        html: String?,
        httpStatus: Int,
        httpReason: String,
        mimeType: String?
    )
}

class WebpageCacheService(
    private val webpageMarkdownRepository: IWebpageMarkdownRepository
) : IWebpageCacheService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    }

    override suspend fun getCachedMarkdown(url: String): CachedWebpageResult {
        val cached = webpageMarkdownRepository.findByUrl(url)
            ?: return CachedWebpageResult.Miss.also {
                logger.debug("Cache miss for URL: {}", url)
            }

        val currentTime = System.currentTimeMillis()
        val age = currentTime - cached.updatedAtEpochMs

        return if (age < CACHE_TTL_MS) {
            logger.debug("Cache hit for URL: {} (age: {} ms)", url, age)
            CachedWebpageResult.Hit(cached)
        } else {
            logger.debug("Cache expired for URL: {} (age: {} ms)", url, age)
            CachedWebpageResult.Expired(cached)
        }
    }

    override suspend fun cacheWebpage(
        url: String,
        markdown: String?,
        html: String?,
        httpStatus: Int,
        httpReason: String,
        mimeType: String?
    ) {
        val currentTime = System.currentTimeMillis()
        val existing = webpageMarkdownRepository.findByUrl(url)

        webpageMarkdownRepository.upsert(
            WebpageMarkdown(
                url = url,
                markdown = markdown,
                html = html,
                httpStatus = httpStatus,
                httpReason = httpReason,
                mimeType = mimeType,
                createdAtEpochMs = existing?.createdAtEpochMs ?: currentTime,
                updatedAtEpochMs = currentTime
            )
        )

        logger.debug("Cached webpage for URL: {} (status: {}, markdown: {} chars)", url, httpStatus, markdown?.length ?: 0)
    }
}


