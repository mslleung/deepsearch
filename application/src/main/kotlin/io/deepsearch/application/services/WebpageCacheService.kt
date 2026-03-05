package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.IndexingTaskType
import io.deepsearch.domain.models.entities.MarkdownIndexingTask
import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.IMarkdownIndexingTaskRepository
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.domain.services.ITextEmbeddingService
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
    data class Failure(val webpageMarkdown: WebpageMarkdown) : CachedWebpageResult()
}

interface IWebpageCacheService {
    /**
     * Get cached markdown for a URL, checking TTL expiration.
     * @param url The normalized URL to look up
     * @param maxCacheAge Cache expiration time in milliseconds (null means no expiry check)
     * @return CachedWebpageResult indicating hit, miss, or expired entry
     */
    suspend fun getCachedMarkdown(url: String, maxCacheAge: Long?): CachedWebpageResult

    /**
     * Cache webpage markdown and metadata for interactive mode.
     * 
     * This method stores the webpage content and triggers async indexing for
     * hybrid search embeddings and knowledge graph extraction.
     * 
     * @param url The normalized URL
     * @param title The webpage title
     * @param description The webpage description
     * @param markdown The extracted markdown content (null if extraction failed)
     * @param html The original HTML (null for non-HTML content)
     * @param httpStatus HTTP status code
     * @param httpReason HTTP reason phrase
     * @param mimeType Content MIME type
     * @param sessionId Session ID for token tracking
     * @param imageMapping Mapping of image numbers to original hash IDs: {"1": "img-abc123"}
     */
    suspend fun cacheWebpage(
        url: String,
        title: String?,
        description: String?,
        markdown: String?,
        html: String?,
        httpStatus: Int,
        httpReason: String,
        mimeType: String?,
        sessionId: SessionId,
        imageMapping: Map<String, String>? = null,
        contentMapJson: String? = null
    )

    /**
     * Cache webpage markdown and metadata for batch mode.
     * 
     * This method only stores the webpage content without triggering async indexing.
     * Indexing is handled separately by batch pipeline stages.
     * 
     * @param url The normalized URL
     * @param title The webpage title
     * @param description The webpage description
     * @param markdown The extracted markdown content (null if extraction failed)
     * @param html The original HTML (null for non-HTML content)
     * @param httpStatus HTTP status code
     * @param httpReason HTTP reason phrase
     * @param mimeType Content MIME type
     * @param sessionId Session ID for token tracking
     * @param imageMapping Mapping of image numbers to original hash IDs: {"1": "img-abc123"}
     * @param fileSearchDocumentName For FILE type URLs: Gemini File Search document name for deletion
     */
    suspend fun cacheWebpageBatch(
        url: String,
        title: String?,
        description: String?,
        markdown: String?,
        html: String?,
        httpStatus: Int,
        httpReason: String,
        mimeType: String?,
        sessionId: SessionId,
        imageMapping: Map<String, String>? = null,
        fileSearchDocumentName: String? = null
    )

    /**
     * Search for similar webpages using hybrid search with Reciprocal Rank Fusion (RRF).
     * Combines keyword-based full-text search with semantic vector similarity search.
     * Returns webpages ordered by combined relevance score (most relevant first).
     *
     * Only searches within webpages that:
     * - Have the specified URL prefix (to limit search to a specific domain/path)
     * - Were updated within the cache expiry window (if maxCacheAge is non-null)
     * - Have non-null embeddings and markdown content
     *
     * @param query The search query text (used for both keyword search and embedding generation)
     * @param baseUrl The base URL to filter by (will be normalized, e.g., "https://example.com")
     * @param maxCacheAge Cache expiration time in milliseconds (null means no expiry filtering)
     * @param limit Maximum number of results to return
     * @param sessionId Session ID for token tracking
     * @return List of WebpageMarkdown objects, ordered by RRF combined score (most relevant first)
     */
    suspend fun searchHybrid(
        query: String,
        baseUrl: String,
        maxCacheAge: Long?,
        limit: Int,
        sessionId: SessionId
    ): List<WebpageMarkdown>
}

@OptIn(ExperimentalTime::class)
class WebpageCacheService(
    private val webpageMarkdownRepository: IWebpageMarkdownRepository,
    private val textEmbeddingService: ITextEmbeddingService,
    private val normalizeUrlService: io.deepsearch.domain.services.INormalizeUrlService,
    private val tokenUsageService: ILlmTokenUsageService,
    private val linkRelevanceHtmlService: ILinkRelevanceHtmlService,
    private val markdownIndexingTaskRepository: IMarkdownIndexingTaskRepository,
    private val markdownIndexingWorker: IMarkdownIndexingWorker
) : IWebpageCacheService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun getCachedMarkdown(url: String, maxCacheAge: Long?): CachedWebpageResult {
        val cached = webpageMarkdownRepository.findByUrl(url)
            ?: return CachedWebpageResult.Miss.also {
                logger.debug("Cache miss for URL: {}", url)
            }

        // Check for cached failure (non-2xx status) - always return Failure to trigger retry
        if (cached.httpStatus != null && cached.httpStatus !in 200..299) {
            logger.debug("Cached failure for URL: {} (status: {})", url, cached.httpStatus)
            return CachedWebpageResult.Failure(cached)
        }

        if (maxCacheAge == null) {
            logger.debug("Cache hit for URL: {} (no expiry check)", url)
            return CachedWebpageResult.Hit(cached)
        }

        val currentTime = Clock.System.now()
        val age = currentTime - cached.updatedAt

        return if (age.inWholeMilliseconds < maxCacheAge) {
            logger.debug(
                "Cache hit for URL: {} (age: {} ms, expiry: {} ms)",
                url,
                age.inWholeMilliseconds,
                maxCacheAge
            )
            CachedWebpageResult.Hit(cached)
        } else {
            logger.debug(
                "Cache expired for URL: {} (age: {} ms, expiry: {} ms)",
                url,
                age.inWholeMilliseconds,
                maxCacheAge
            )
            CachedWebpageResult.Expired(cached)
        }
    }

    override suspend fun cacheWebpage(
        url: String,
        title: String?,
        description: String?,
        markdown: String?,
        html: String?,
        httpStatus: Int,
        httpReason: String,
        mimeType: String?,
        sessionId: SessionId,
        imageMapping: Map<String, String>?,
        contentMapJson: String?
    ) {
        cacheWebpageInternal(url, title, description, markdown, html, httpStatus, httpReason, mimeType, imageMapping = imageMapping, contentMapJson = contentMapJson)

        if (!markdown.isNullOrBlank()) {
            val tasks = listOf(
                MarkdownIndexingTask.createPending(
                    url = url,
                    taskType = IndexingTaskType.EMBEDDING,
                    sessionId = sessionId.toStorageString(),
                    markdown = markdown
                ),
                MarkdownIndexingTask.createPending(
                    url = url,
                    taskType = IndexingTaskType.KNOWLEDGE_GRAPH,
                    sessionId = sessionId.toStorageString(),
                    markdown = markdown
                )
            )

            markdownIndexingTaskRepository.createBatch(tasks)
            markdownIndexingWorker.notifyNewTasks()
            
            logger.debug("Created {} indexing task(s) for URL: {}", tasks.size, url)
        }
    }

    override suspend fun cacheWebpageBatch(
        url: String,
        title: String?,
        description: String?,
        markdown: String?,
        html: String?,
        httpStatus: Int,
        httpReason: String,
        mimeType: String?,
        sessionId: SessionId,
        imageMapping: Map<String, String>?,
        fileSearchDocumentName: String?
    ) {
        cacheWebpageInternal(url, title, description, markdown, html, httpStatus, httpReason, mimeType, fileSearchDocumentName = fileSearchDocumentName, imageMapping = imageMapping)
    }

    private suspend fun cacheWebpageInternal(
        url: String,
        title: String?,
        description: String?,
        markdown: String?,
        html: String?,
        httpStatus: Int,
        httpReason: String,
        mimeType: String?,
        fileSearchDocumentName: String? = null,
        imageMapping: Map<String, String>? = null,
        contentMapJson: String? = null
    ) {
        val currentTime = Clock.System.now()
        val existing = webpageMarkdownRepository.findByUrl(url)

        val cleanedLinkRelevanceHtml = if (!html.isNullOrBlank()) {
            linkRelevanceHtmlService.prepareLinkRelevanceHtml(html, url).cleanedHtml
        } else {
            null
        }

        webpageMarkdownRepository.upsert(
            WebpageMarkdown(
                url = url,
                title = title,
                description = description,
                markdown = markdown,
                cleanedLinkRelevanceHtml = cleanedLinkRelevanceHtml,
                httpStatus = httpStatus,
                httpReason = httpReason,
                mimeType = mimeType,
                embedding = null,
                fileSearchDocumentName = fileSearchDocumentName,
                imageMapping = imageMapping,
                contentMapJson = contentMapJson ?: existing?.contentMapJson,
                createdAt = existing?.createdAt ?: currentTime,
                updatedAt = currentTime,
                version = existing?.version ?: 0
            )
        )

        logger.debug(
            "Cached webpage for URL: {} (status: {}, markdown: {} chars, linkRelevanceHtml: {} chars)",
            url, httpStatus, markdown?.length ?: 0, cleanedLinkRelevanceHtml?.length ?: 0
        )
    }

    override suspend fun searchHybrid(
        query: String,
        baseUrl: String,
        maxCacheAge: Long?,
        limit: Int,
        sessionId: SessionId
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
            logger.debug(
                "Hybrid search: Generated query embedding with {} dimensions (used {} tokens)",
                queryEmbedding.size, embeddingResult.tokenUsage.totalTokens
            )

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
            val minUpdatedAtEpochMs = if (maxCacheAge != null) {
                val currentTime = Clock.System.now()
                val timestamp = currentTime.toEpochMilliseconds() - maxCacheAge
                logger.debug("Hybrid search: Min timestamp = {}", timestamp)
                timestamp
            } else {
                logger.debug("Hybrid search: No timestamp filtering (maxCacheAge is null)")
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
