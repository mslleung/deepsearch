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
     * @param cacheExpiryMs Cache expiration time in milliseconds (null means no expiry check)
     * @return CachedWebpageResult indicating hit, miss, or expired entry
     */
    suspend fun getCachedMarkdown(url: String, cacheExpiryMs: Long?): CachedWebpageResult

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

    /**
     * Search for similar webpages using vector cosine distance.
     * Returns webpages ordered by similarity (most similar first).
     *
     * Only searches within webpages that:
     * - Have the specified URL prefix (to limit search to a specific domain/path)
     * - Were updated within the cache expiry window (if cacheExpiryMs is non-null)
     * - Have non-null embeddings
     *
     * @param query The search query text
     * @param baseUrl The base URL to filter by (will be normalized, e.g., "https://example.com")
     * @param cacheExpiryMs Cache expiration time in milliseconds (null means no expiry filtering)
     * @param limit Maximum number of results to return
     * @return List of WebpageMarkdown objects, ordered by similarity (most similar first)
     */
    suspend fun searchSimilar(
        query: String,
        baseUrl: String,
        cacheExpiryMs: Long?,
        limit: Int
    ): List<WebpageMarkdown>
}

@OptIn(ExperimentalTime::class)
class WebpageCacheService(
    private val webpageMarkdownRepository: IWebpageMarkdownRepository,
    private val applicationScope: IApplicationCoroutineScope,
    private val textEmbeddingService: ITextEmbeddingService,
    private val normalizeUrlService: io.deepsearch.domain.services.INormalizeUrlService
) : IWebpageCacheService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    /**
     * Maximum markdown length to embed (characters).
     * Truncated to avoid hitting token limits and reduce embedding cost.
     * 50K characters is roughly ~12.5K tokens for English text.
     */
    private val maxMarkdownLength = 50_000

    override suspend fun getCachedMarkdown(url: String, cacheExpiryMs: Long?): CachedWebpageResult {
        val cached = webpageMarkdownRepository.findByUrl(url)
            ?: return CachedWebpageResult.Miss.also {
                logger.debug("Cache miss for URL: {}", url)
            }

        // If no expiry time is specified, always return Hit
        if (cacheExpiryMs == null) {
            logger.debug("Cache hit for URL: {} (no expiry check)", url)
            return CachedWebpageResult.Hit(cached)
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


    override suspend fun searchSimilar(
        query: String,
        baseUrl: String,
        cacheExpiryMs: Long?,
        limit: Int
    ): List<WebpageMarkdown> {
        // Normalize URL to get prefix for filtering
        val urlPrefix = normalizeUrlService.normalize(baseUrl) ?: baseUrl
        logger.debug("Vector search: URL prefix = {}", urlPrefix)

        // Generate query embedding
        // Include the url in the query to increase the likelihood of getting documents with the url prefix
        // This is because pgvector applies filtering after retrieving from vector db
        val queryEmbedding = textEmbeddingService.embedQuery("$query $baseUrl")
        logger.debug("Vector search: Generated query embedding with {} dimensions", queryEmbedding.size)

        // Calculate minimum updated timestamp for cache expiry (null if no expiry filtering)
        val minUpdatedAtEpochMs = if (cacheExpiryMs != null) {
            val currentTime = Clock.System.now()
            val timestamp = currentTime.toEpochMilliseconds() - cacheExpiryMs
            logger.debug("Vector search: Min timestamp = {}", timestamp)
            timestamp
        } else {
            logger.debug("Vector search: No timestamp filtering (cacheExpiryMs is null)")
            null
        }

        // Search for similar embeddings
        return webpageMarkdownRepository.searchSimilar(
            queryEmbedding = queryEmbedding,
            urlPrefix = urlPrefix,
            minUpdatedAtEpochMs = minUpdatedAtEpochMs,
            limit = limit
        )
    }
}


