package io.deepsearch.application.services

import io.deepsearch.application.services.batch.PageHtmlWithBoundingBoxes
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageTable
import io.deepsearch.domain.models.valueobjects.BatchUrlStateId
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.IWebpageTableRepository
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.time.ExperimentalTime

import io.deepsearch.domain.services.BatchContentRequest

/**
 * Result of preparing table identification batch requests.
 */
data class TableIdentificationBatchPreparation(
    /** Map of URL state ID to cached table identifications (if found in cache) */
    val cachedResults: Map<BatchUrlStateId, List<TableIdentification>>,
    /** Batch requests for uncached pages */
    val batchRequests: List<BatchContentRequest>,
    /** Map of request index -> URL state ID */
    val requestIndexToUrlStateId: Map<Int, BatchUrlStateId>,
    /** Map of URL state ID -> cleaned HTML with injected IDs (for parsing results) */
    val htmlWithIdsMap: Map<BatchUrlStateId, String>
)

interface ITableIdentificationService {
    /**
     * Identifies tables in webpage HTML using an LLM agent.
     * 
     * Only requires the pre-captured page snapshot - no live browser needed.
     * The browser can be released before table identification begins.
     */
    suspend fun identifyTables(
        sessionId: SessionId,
        pageSnapshot: IBrowserPage.PageSnapshotWithMetadata
    ): List<TableIdentification>
    
    // ========== Batch API Methods ==========
    
    /**
     * Prepare batch requests for table identification with cache check.
     * 
     * @param pages Map of URL state ID -> page HTML with bounding boxes
     * @param jobId Batch job ID for request ID generation
     * @return Cached results and batch requests for uncached pages
     */
    suspend fun prepareBatchRequests(
        pages: Map<BatchUrlStateId, PageHtmlWithBoundingBoxes>,
        jobId: Long
    ): TableIdentificationBatchPreparation
    
    /**
     * Parse batch response and return table identifications.
     * 
     * @param responseText JSON response from batch API
     * @param htmlWithIds HTML with injected IDs (from TableIdentificationBatchPreparation.htmlWithIdsMap)
     * @return List of TableIdentification
     */
    fun parseBatchResponse(responseText: String, htmlWithIds: String): List<TableIdentification>
    
    /**
     * Cache table identification result.
     * 
     * @param htmlHash SHA-256 hash of the original HTML
     * @param tables List of identified tables to cache
     */
    suspend fun cacheResult(htmlHash: ByteArray, tables: List<TableIdentification>)
}

class TableIdentificationService(
    private val tableIdentificationAgent: ITableIdentificationAgent,
    private val webpageTableRepository: IWebpageTableRepository,
    private val tokenUsageService: ILlmTokenUsageService
) : ITableIdentificationService {

    private val logger = org.slf4j.LoggerFactory.getLogger(this::class.java)

    // ========== Batch API Methods ==========

    override suspend fun prepareBatchRequests(
        pages: Map<BatchUrlStateId, PageHtmlWithBoundingBoxes>,
        jobId: Long
    ): TableIdentificationBatchPreparation {
        if (pages.isEmpty()) {
            return TableIdentificationBatchPreparation(emptyMap(), emptyList(), emptyMap(), emptyMap())
        }

        val cachedResults = mutableMapOf<BatchUrlStateId, List<TableIdentification>>()
        val batchRequests = mutableListOf<BatchContentRequest>()
        val requestIndexToUrlStateId = mutableMapOf<Int, BatchUrlStateId>()
        val htmlWithIdsMap = mutableMapOf<BatchUrlStateId, String>()

        for ((urlStateId, pageData) in pages) {
            val htmlHash = MessageDigest.getInstance("SHA-256").digest(pageData.html.toByteArray())

            // Check cache
            val existing = webpageTableRepository.findByHash(htmlHash)
            if (existing != null) {
                cachedResults[urlStateId] = Json.decodeFromString<List<TableIdentification>>(existing.tables)
                continue
            }

            // Prepare batch request using agent
            val batchRequest = tableIdentificationAgent.prepareBatchRequest(
                requestId = "$jobId-table-${urlStateId.value}",
                html = pageData.html
            )
            
            requestIndexToUrlStateId[batchRequests.size] = urlStateId
            batchRequests.add(batchRequest.request)
            htmlWithIdsMap[urlStateId] = batchRequest.htmlWithIds
        }

        logger.debug("Table ID batch: {} cached, {} need processing", cachedResults.size, batchRequests.size)

        return TableIdentificationBatchPreparation(
            cachedResults = cachedResults,
            batchRequests = batchRequests,
            requestIndexToUrlStateId = requestIndexToUrlStateId,
            htmlWithIdsMap = htmlWithIdsMap
        )
    }

    override fun parseBatchResponse(responseText: String, htmlWithIds: String): List<TableIdentification> {
        return tableIdentificationAgent.parseBatchResponse(responseText, htmlWithIds)
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun cacheResult(htmlHash: ByteArray, tables: List<TableIdentification>) {
        webpageTableRepository.upsert(
            WebpageTable(
                webpageHtmlHash = htmlHash,
                tables = Json.encodeToString(tables)
            )
        )
    }

    // ========== Interactive Mode Methods ==========

    /**
     * Identifies tables in webpage HTML using an LLM agent.
     * Results are cached in the repository to avoid repeated calls with the same HTML.
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun identifyTables(
        sessionId: SessionId,
        pageSnapshot: IBrowserPage.PageSnapshotWithMetadata
    ): List<TableIdentification> {
        // Use HTML hash for caching
        val htmlHash = MessageDigest.getInstance("SHA-256").digest(pageSnapshot.html.toByteArray())

        val existing = webpageTableRepository.findByHash(htmlHash)
        if (existing != null) {
            return Json.decodeFromString<List<TableIdentification>>(existing.tables)
        }

        val agentOutput = tableIdentificationAgent.generate(
            TableIdentificationInput(
                pageSnapshot = pageSnapshot
            )
        )

        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "TableIdentificationAgent",
            modelName = agentOutput.tokenUsage.modelName,
            promptTokens = agentOutput.tokenUsage.promptTokens,
            outputTokens = agentOutput.tokenUsage.outputTokens,
            totalTokens = agentOutput.tokenUsage.totalTokens
        )

        val tables = agentOutput.tables

        webpageTableRepository.upsert(
            WebpageTable(
                webpageHtmlHash = htmlHash,
                tables = Json.encodeToString(tables)
            )
        )

        return tables
    }
}

