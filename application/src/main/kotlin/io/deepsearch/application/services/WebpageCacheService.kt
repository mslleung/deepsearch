package io.deepsearch.application.services

import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.domain.services.ITextEmbeddingService
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
     * @param cacheExpiryMs Cache expiration time in milliseconds (default 7 days)
     * @return CachedWebpageResult indicating hit, miss, or expired entry
     */
    suspend fun getCachedMarkdown(url: String, cacheExpiryMs: Long = 604800000L): CachedWebpageResult

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

@OptIn(ExperimentalTime::class)
class WebpageCacheService(
    private val webpageMarkdownRepository: IWebpageMarkdownRepository,
    private val applicationScope: IApplicationCoroutineScope,
    private val textEmbeddingService: ITextEmbeddingService
) : IWebpageCacheService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    /**
     * Maximum markdown length to embed (characters).
     * Truncated to avoid hitting token limits and reduce embedding cost.
     * 50K characters is roughly ~12.5K tokens for English text.
     */
    private val maxMarkdownLength = 50_000

    override suspend fun getCachedMarkdown(url: String, cacheExpiryMs: Long): CachedWebpageResult {
        val cached = webpageMarkdownRepository.findByUrl(url)
            ?: return CachedWebpageResult.Miss.also {
                logger.debug("Cache miss for URL: {}", url)
            }

        val currentTime = Clock.System.now()
        val age = currentTime - cached.updatedAt

        return if (age.inWholeMilliseconds < cacheExpiryMs) {
            logger.debug("Cache hit for URL: {} (age: {} ms, expiry: {} ms)", url, age.inWholeMilliseconds, cacheExpiryMs)
            CachedWebpageResult.Hit(cached)
        } else {
            logger.debug("Cache expired for URL: {} (age: {} ms, expiry: {} ms)", url, age.inWholeMilliseconds, cacheExpiryMs)
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
        val currentTime = Clock.System.now()
        val existing = webpageMarkdownRepository.findByUrl(url)

        // Store webpage without embedding first
        webpageMarkdownRepository.upsert(
            WebpageMarkdown(
                url = url,
                markdown = markdown,
                html = html,
                httpStatus = httpStatus,
                httpReason = httpReason,
                mimeType = mimeType,
                embedding = null, // Will be updated asynchronously
                createdAt = existing?.createdAt ?: currentTime,
                updatedAt = currentTime
            )
        )

        logger.debug("Cached webpage for URL: {} (status: {}, markdown: {} chars)", url, httpStatus, markdown?.length ?: 0)
        
        // Generate and store embedding asynchronously if markdown is available
        if (markdown != null && markdown.isNotBlank()) {
            generateAndStoreEmbeddingAsync(url, markdown)
        }
    }
    
    /**
     * Generate and store an embedding for a markdown document asynchronously.
     * This method launches a coroutine and returns immediately (fire-and-forget).
     * 
     * If embedding generation or storage fails, the error is logged but not propagated.
     * This ensures that embedding failures don't break the main request flow.
     */
    private fun generateAndStoreEmbeddingAsync(url: String, markdown: String) {
        // Launch in application scope (fire-and-forget)
        applicationScope.scope.launch {
            try {
                logger.debug("Generating embedding for URL: {}", url)
                
                // Truncate markdown to avoid token limits
                val truncatedMarkdown = if (markdown.length > maxMarkdownLength) {
                    logger.debug(
                        "Truncating markdown from {} to {} chars for URL: {}", 
                        markdown.length, 
                        maxMarkdownLength, 
                        url
                    )
                    markdown.substring(0, maxMarkdownLength)
                } else {
                    markdown
                }
                
                // Generate embedding (returns list with single embedding)
                val embeddings = textEmbeddingService.embedDocuments(listOf(truncatedMarkdown))
                
                if (embeddings.isEmpty()) {
                    logger.error("No embedding returned for URL: {}", url)
                    return@launch
                }
                
                val embedding = embeddings[0]
                logger.debug("Generated embedding with {} dimensions for URL: {}", embedding.size, url)
                
                // Fetch current webpage data and update with embedding
                val existing = webpageMarkdownRepository.findByUrl(url)
                if (existing != null) {
                    webpageMarkdownRepository.upsert(
                        existing.copy(
                            embedding = embedding,
                            updatedAt = Clock.System.now()
                        )
                    )
                    logger.debug("Successfully stored embedding for URL: {}", url)
                } else {
                    logger.warn("Webpage not found for URL {} when trying to store embedding", url)
                }
                
            } catch (e: Exception) {
                // Log error but don't propagate - embedding generation is best-effort
                logger.error(
                    "Failed to generate/store embedding for URL {}: {}", 
                    url, 
                    e.message, 
                    e
                )
            }
        }
    }
}


