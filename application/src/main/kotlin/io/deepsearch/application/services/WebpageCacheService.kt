package io.deepsearch.application.services

import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.domain.services.ITextEmbeddingService
import kotlinx.coroutines.delay
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
     * @param sessionId Query session ID for token tracking
     */
    suspend fun cacheWebpage(
        url: String,
        markdown: String?,
        html: String?,
        httpStatus: Int,
        httpReason: String,
        mimeType: String?,
        sessionId: String
    )

    /**
     * Search for similar webpages using hybrid search with Reciprocal Rank Fusion (RRF).
     * Combines keyword-based full-text search with semantic vector similarity search.
     * Returns webpages ordered by combined relevance score (most relevant first).
     *
     * Only searches within webpages that:
     * - Have the specified URL prefix (to limit search to a specific domain/path)
     * - Were updated within the cache expiry window (if cacheExpiryMs is non-null)
     * - Have non-null embeddings and markdown content
     *
     * @param query The search query text (used for both keyword search and embedding generation)
     * @param baseUrl The base URL to filter by (will be normalized, e.g., "https://example.com")
     * @param cacheExpiryMs Cache expiration time in milliseconds (null means no expiry filtering)
     * @param limit Maximum number of results to return
     * @param sessionId Query session ID for token tracking
     * @return List of WebpageMarkdown objects, ordered by RRF combined score (most relevant first)
     */
    suspend fun searchHybrid(
        query: String,
        baseUrl: String,
        cacheExpiryMs: Long?,
        limit: Int,
        sessionId: String
    ): List<WebpageMarkdown>
}

@OptIn(ExperimentalTime::class)
class WebpageCacheService(
    private val webpageMarkdownRepository: IWebpageMarkdownRepository,
    private val applicationScope: IApplicationCoroutineScope,
    private val textEmbeddingService: ITextEmbeddingService,
    private val normalizeUrlService: io.deepsearch.domain.services.INormalizeUrlService,
    private val tokenUsageService: ILlmTokenUsageService
) : IWebpageCacheService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

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
            logger.debug(
                "Cache hit for URL: {} (age: {} ms, expiry: {} ms)",
                url,
                age.inWholeMilliseconds,
                cacheExpiryMs
            )
            CachedWebpageResult.Hit(cached)
        } else {
            logger.debug(
                "Cache expired for URL: {} (age: {} ms, expiry: {} ms)",
                url,
                age.inWholeMilliseconds,
                cacheExpiryMs
            )
            CachedWebpageResult.Expired(cached)
        }
    }

    override suspend fun cacheWebpage(
        url: String,
        markdown: String?,
        html: String?,
        httpStatus: Int,
        httpReason: String,
        mimeType: String?,
        sessionId: String
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

        logger.debug(
            "Cached webpage for URL: {} (status: {}, markdown: {} chars)",
            url,
            httpStatus,
            markdown?.length ?: 0
        )

        // Generate and store embedding asynchronously if markdown is available
        if (markdown != null && markdown.isNotBlank()) {
            generateAndStoreEmbeddingAsync(url, markdown, sessionId)
        }
    }

    /**
     * Generate and store an embedding for a markdown document asynchronously.
     * This method launches a coroutine and returns immediately (fire-and-forget).
     *
     * If embedding generation or storage fails, the error is logged but not propagated.
     * This ensures that embedding failures don't break the main request flow.
     */
    private fun generateAndStoreEmbeddingAsync(url: String, markdown: String, sessionId: String) {
        // Launch in application scope (fire-and-forget)
        applicationScope.scope.launch {
            try {
                logger.debug("Generating embedding for URL: {}", url)

                // Generate embedding (returns EmbeddingResult with embeddings and token usage)
                val result = textEmbeddingService.embedDocuments(listOf(markdown))

                if (result.embeddings.isEmpty()) {
                    logger.error("No embedding returned for URL: {}", url)
                    return@launch
                }

                val embedding = result.embeddings[0]
                logger.debug("Generated embedding with {} dimensions for URL: {} (used {} tokens)", 
                    embedding.size, url, result.tokenUsage.totalTokens)

                // Record token usage
                tokenUsageService.recordTokenUsage(
                    sessionId = sessionId,
                    agentName = "WebpageCacheService.embedDocuments",
                    modelName = result.tokenUsage.modelName,
                    promptTokens = result.tokenUsage.promptTokens,
                    outputTokens = result.tokenUsage.outputTokens,
                    totalTokens = result.tokenUsage.totalTokens
                )

                // Retry logic for optimistic lock exceptions
                var retries = 0
                val maxRetries = 3
                while (retries < maxRetries) {
                    try {
                        // Fetch current webpage data and update with embedding
                        val existing = webpageMarkdownRepository.findByUrl(url)
                        if (existing != null) {
                            webpageMarkdownRepository.upsert(
                                existing.copy(
                                    embedding = embedding,
                                    updatedAt = Clock.System.now()
                                )
                            )
                            logger.debug("Successfully stored embedding for URL: {} (attempt {})", url, retries + 1)
                            break // Success, exit retry loop
                        } else {
                            logger.warn("Webpage not found for URL {} when trying to store embedding", url)
                            break // No point retrying if record doesn't exist
                        }
                    } catch (e: OptimisticLockException) {
                        retries++
                        if (retries < maxRetries) {
                            logger.debug("Optimistic lock conflict storing embedding for URL {}, retrying (attempt {}/{})", url, retries + 1, maxRetries)
                            delay(100L * retries) // Exponential backoff: 100ms, 200ms, 300ms
                        } else {
                            logger.warn("Failed to store embedding for URL {} after {} retries due to optimistic lock conflicts", url, maxRetries)
                            throw e // Re-throw to be caught by outer catch block
                        }
                    }
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

    override suspend fun searchHybrid(
        query: String,
        baseUrl: String,
        cacheExpiryMs: Long?,
        limit: Int,
        sessionId: String
    ): List<WebpageMarkdown> {
        try {
            // Normalize URL to get prefix for filtering
            val urlPrefix = normalizeUrlService.normalize(baseUrl) ?: baseUrl
            logger.debug("Hybrid search: URL prefix = {}", urlPrefix)

            // Generate query embedding
            // Include the url in the query to increase the likelihood of getting documents with the url prefix
            // This is because pgvector applies filtering after retrieving from vector db
            val embeddingResult = textEmbeddingService.embedQuery("$query $baseUrl")
            val queryEmbedding = embeddingResult.embedding
            logger.debug("Hybrid search: Generated query embedding with {} dimensions (used {} tokens)", 
                queryEmbedding.size, embeddingResult.tokenUsage.totalTokens)

            // Record token usage
            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "WebpageCacheService.embedQuery",
                modelName = embeddingResult.tokenUsage.modelName,
                promptTokens = embeddingResult.tokenUsage.promptTokens,
                outputTokens = embeddingResult.tokenUsage.outputTokens,
                totalTokens = embeddingResult.tokenUsage.totalTokens
            )

            // Calculate minimum updated timestamp for cache expiry (null if no expiry filtering)
            val minUpdatedAtEpochMs = if (cacheExpiryMs != null) {
                val currentTime = Clock.System.now()
                val timestamp = currentTime.toEpochMilliseconds() - cacheExpiryMs
                logger.debug("Hybrid search: Min timestamp = {}", timestamp)
                timestamp
            } else {
                logger.debug("Hybrid search: No timestamp filtering (cacheExpiryMs is null)")
                null
            }

            // Search using hybrid search (RRF combining keyword + semantic search)
            return webpageMarkdownRepository.searchHybrid(
                textQuery = query,
                queryEmbedding = queryEmbedding,
                urlPrefix = urlPrefix,
                minUpdatedAtEpochMs = minUpdatedAtEpochMs,
                limit = limit
            )
        } catch (e: Exception) {
            logger.error("Hybrid search failed: {}", e.message, e)
            throw e
        }
    }
}


