package io.deepsearch.application.services

import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.WebpageMarkdown
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.domain.services.BatchEmbeddingRequest
import io.deepsearch.domain.services.BatchResult
import io.deepsearch.domain.services.ITextEmbeddingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Service for managing hybrid search indexing (embeddings).
 * 
 * Handles both interactive (fire-and-forget) and batch modes.
 */
interface IHybridSearchIndexingService {
    /**
     * Index a document for hybrid search asynchronously (fire-and-forget).
     * Used by interactive mode.
     * 
     * @param url The URL of the document
     * @param markdown The markdown content to embed
     * @param sessionId Session ID for token tracking
     * @param isPreview Whether this is preview content (for race protection)
     */
    fun indexAsync(
        url: String,
        markdown: String,
        sessionId: SessionId,
        isPreview: Boolean = false
    )

    /**
     * Prepare a batch embedding request.
     * Used by batch mode to collect requests before submission.
     * 
     * @param requestId Unique identifier for this request
     * @param markdown The markdown content to embed
     * @return BatchEmbeddingRequest for the Gemini Batch API
     */
    fun prepareBatchRequest(requestId: String, markdown: String): BatchEmbeddingRequest

    /**
     * Process batch embedding results and store them.
     * Called after batch API returns results.
     * 
     * @param results Map of URL to embedding vector
     */
    suspend fun processBatchResults(results: Map<String, List<Float>>)
}

@OptIn(ExperimentalTime::class)
class HybridSearchIndexingService(
    private val webpageMarkdownRepository: IWebpageMarkdownRepository,
    private val applicationScope: IApplicationCoroutineScope,
    private val textEmbeddingService: ITextEmbeddingService,
    private val tokenUsageService: ILlmTokenUsageService
) : IHybridSearchIndexingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val EMBEDDING_MODEL = "gemini-embedding-001"
        private const val EMBEDDING_DIMENSIONALITY = 1536
    }

    override fun indexAsync(
        url: String,
        markdown: String,
        sessionId: SessionId,
        isPreview: Boolean
    ) {
        if (markdown.isBlank()) {
            logger.debug("Skipping embedding for URL {} - empty markdown", url)
            return
        }

        // Launch in application scope (fire-and-forget)
        applicationScope.scope.launch {
            try {
                logger.debug("Generating embedding for URL: {} (isPreview: {})", url, isPreview)

                // Generate embedding
                val result = textEmbeddingService.embedDocuments(listOf(markdown))

                if (result.embeddings.isEmpty()) {
                    logger.error("No embedding returned for URL: {}", url)
                    return@launch
                }

                val embedding = result.embeddings[0]
                logger.debug(
                    "Generated embedding with {} dimensions for URL: {} (used {} tokens)",
                    embedding.size, url, result.tokenUsage.totalTokens
                )

                // Record token usage
                tokenUsageService.recordTokenUsage(
                    sessionId = sessionId,
                    agentName = "HybridSearchIndexingService.embedDocuments",
                    modelName = result.tokenUsage.modelName,
                    promptTokens = result.tokenUsage.promptTokens,
                    outputTokens = result.tokenUsage.outputTokens,
                    totalTokens = result.tokenUsage.totalTokens
                )

                // Store embedding with retry logic
                storeEmbeddingWithRetry(url, embedding, isPreview)

            } catch (e: Exception) {
                logger.error("Failed to generate/store embedding for URL {}: {}", url, e.message, e)
            }
        }
    }

    override fun prepareBatchRequest(requestId: String, markdown: String): BatchEmbeddingRequest {
        return BatchEmbeddingRequest(
            requestId = requestId,
            modelId = EMBEDDING_MODEL,
            text = markdown,
            taskType = "RETRIEVAL_DOCUMENT",
            outputDimensionality = EMBEDDING_DIMENSIONALITY
        )
    }

    override suspend fun processBatchResults(results: Map<String, List<Float>>) {
        logger.info("Processing {} batch embedding results", results.size)

        results.forEach { (url, embedding) ->
            try {
                storeEmbeddingWithRetry(url, embedding, isPreview = false)
                logger.debug("Stored batch embedding for URL: {}", url)
            } catch (e: Exception) {
                logger.warn("Failed to store batch embedding for URL {}: {}", url, e.message)
            }
        }

        logger.info("Completed processing batch embedding results")
    }

    private suspend fun storeEmbeddingWithRetry(
        url: String,
        embedding: List<Float>,
        isPreview: Boolean
    ) {
        var retries = 0
        val maxRetries = 3

        while (retries < maxRetries) {
            try {
                val existing = webpageMarkdownRepository.findByUrl(url)
                if (existing != null) {
                    // Race protection: don't let preview embedding overwrite full markdown embedding
                    if (isPreview && !existing.isPreview) {
                        logger.debug("Skipping preview embedding for URL {} - full markdown already stored", url)
                        return
                    }

                    webpageMarkdownRepository.upsert(
                        existing.copy(
                            embedding = embedding,
                            updatedAt = Clock.System.now()
                        )
                    )
                    logger.debug(
                        "Stored embedding for URL: {} (attempt {}, isPreview: {})",
                        url, retries + 1, isPreview
                    )
                    return
                } else {
                    logger.warn("Webpage not found for URL {} when trying to store embedding", url)
                    return
                }
            } catch (e: OptimisticLockException) {
                retries++
                if (retries < maxRetries) {
                    logger.debug(
                        "Optimistic lock conflict storing embedding for URL {}, retrying (attempt {}/{})",
                        url, retries + 1, maxRetries
                    )
                    delay(100L * retries)
                } else {
                    logger.warn(
                        "Failed to store embedding for URL {} after {} retries due to optimistic lock conflicts",
                        url, maxRetries
                    )
                    throw e
                }
            }
        }
    }
}

