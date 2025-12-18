package io.deepsearch.application.services

import io.deepsearch.application.services.batch.PageHtmlWithBoundingBoxes
import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.SemanticIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.entities.WebpageSemanticElement
import io.deepsearch.domain.models.valueobjects.BatchUrlStateId
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.repositories.IWebpageNavigationElementRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.time.ExperimentalTime

import io.deepsearch.domain.services.BatchContentRequest

/**
 * A pending batch request bundling the key, request, and additional data needed for processing.
 * Used by Semantic and Table Identification services.
 */
data class PendingHtmlBatchRequest(
    val urlStateId: BatchUrlStateId,
    val request: BatchContentRequest,
    val htmlWithIds: String
)

/**
 * Result of preparing semantic identification batch requests.
 */
data class SemanticBatchPreparation(
    /** Map of URL state ID to cached SemanticElements (if found in cache) */
    val cachedResults: Map<BatchUrlStateId, SemanticElements>,
    /** Pending requests for uncached pages (bundled with key and additional data) */
    val pendingRequests: List<PendingHtmlBatchRequest>
)

interface ISemanticIdentificationService {
    /**
     * Identifies all semantic elements (navigation elements + popups) on a webpage.
     * Uses a hash-based cache to avoid redundant LLM calls for similar page layouts.
     *
     * Only requires the pre-captured page snapshot - no live browser needed.
     * The browser can be released before semantic identification begins.
     *
     * @param sessionId Session ID for token tracking
     * @param pageSnapshot Pre-captured page snapshot containing HTML and bounding boxes
     * @return SemanticElements containing all identified semantic elements grouped by type
     */
    suspend fun identifySemanticElements(
        sessionId: SessionId,
        pageSnapshot: IBrowserPage.PageSnapshotWithMetadata
    ): SemanticElements
    
    // ========== Batch API Methods ==========
    
    /**
     * Prepare batch requests for semantic identification with cache check.
     * 
     * @param pages Map of URL state ID -> page HTML with bounding boxes
     * @param jobId Batch job ID for request ID generation
     * @return Cached results and batch requests for uncached pages
     */
    suspend fun prepareBatchRequests(
        pages: Map<BatchUrlStateId, PageHtmlWithBoundingBoxes>,
        jobId: Long
    ): SemanticBatchPreparation
    
    /**
     * Parse batch response and return SemanticElements.
     * 
     * @param responseText JSON response from batch API
     * @param htmlWithIds HTML with injected IDs (from SemanticBatchPreparation.htmlWithIdsMap)
     * @return Parsed SemanticElements
     */
    fun parseBatchResponse(responseText: String, htmlWithIds: String): SemanticElements
    
    /**
     * Cache semantic elements result.
     * 
     * @param htmlHash SHA-256 hash of the original HTML
     * @param elements Semantic elements to cache
     */
    suspend fun cacheResult(htmlHash: ByteArray, elements: SemanticElements)
}

class SemanticIdentificationService(
    private val semanticIdentificationAgent: ISemanticIdentificationAgent,
    private val webpageSemanticElementRepository: IWebpageNavigationElementRepository,
    private val tokenUsageService: ILlmTokenUsageService
) : ISemanticIdentificationService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    // ========== Batch API Methods ==========

    override suspend fun prepareBatchRequests(
        pages: Map<BatchUrlStateId, PageHtmlWithBoundingBoxes>,
        jobId: Long
    ): SemanticBatchPreparation {
        if (pages.isEmpty()) {
            return SemanticBatchPreparation(emptyMap(), emptyList())
        }

        val cachedResults = mutableMapOf<BatchUrlStateId, SemanticElements>()
        val pendingRequests = mutableListOf<PendingHtmlBatchRequest>()

        for ((urlStateId, pageData) in pages) {
            val pageHash = MessageDigest.getInstance("SHA-256").digest(pageData.html.toByteArray())

            // Check cache
            val cached = webpageSemanticElementRepository.findByHash(pageHash)
            if (cached != null) {
                cachedResults[urlStateId] = cached.elements
                continue
            }

            // Prepare batch request using agent
            val batchRequest = semanticIdentificationAgent.prepareBatchRequest(
                requestId = "$jobId-semantic-${urlStateId.value}",
                html = pageData.html
            )
            
            pendingRequests.add(
                PendingHtmlBatchRequest(
                    urlStateId = urlStateId,
                    request = batchRequest.request,
                    htmlWithIds = batchRequest.htmlWithIds
                )
            )
        }

        logger.debug("Semantic batch: {} cached, {} need processing", cachedResults.size, pendingRequests.size)

        return SemanticBatchPreparation(
            cachedResults = cachedResults,
            pendingRequests = pendingRequests
        )
    }

    override fun parseBatchResponse(responseText: String, htmlWithIds: String): SemanticElements {
        return semanticIdentificationAgent.parseBatchResponse(responseText, htmlWithIds)
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun cacheResult(htmlHash: ByteArray, elements: SemanticElements) {
        webpageSemanticElementRepository.upsert(
            WebpageSemanticElement(
                pageHash = htmlHash,
                elements = elements
            )
        )
    }

    // ========== Interactive Mode Methods ==========

    override suspend fun identifySemanticElements(
        sessionId: SessionId,
        pageSnapshot: IBrowserPage.PageSnapshotWithMetadata
    ): SemanticElements {
        // Use html hash for caching
        val pageHash = MessageDigest.getInstance("SHA-256").digest(pageSnapshot.html.toByteArray())

        val cached = webpageSemanticElementRepository.findByHash(pageHash)
        if (cached != null) {
            logger.debug("Using cached semantic elements")
            return cached.elements
        }

        val identificationResult = semanticIdentificationAgent.generate(
            SemanticIdentificationInput(
                pageSnapshot = pageSnapshot
            )
        )

        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "SemanticIdentificationAgent",
            modelName = identificationResult.tokenUsage.modelName,
            promptTokens = identificationResult.tokenUsage.promptTokens,
            outputTokens = identificationResult.tokenUsage.outputTokens,
            totalTokens = identificationResult.tokenUsage.totalTokens
        )

        val elements = identificationResult.elements

        cacheSemanticElements(pageHash, elements)
        return elements
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun cacheSemanticElements(
        pageHash: ByteArray,
        elements: SemanticElements
    ) {
        webpageSemanticElementRepository.upsert(
            WebpageSemanticElement(
                pageHash = pageHash,
                elements = elements
            )
        )
    }
}

