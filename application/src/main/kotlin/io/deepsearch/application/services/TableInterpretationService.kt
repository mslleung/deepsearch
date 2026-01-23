package io.deepsearch.application.services

import io.deepsearch.application.services.batch.TableKey
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.SnippetClassification
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.models.entities.WebpageTableInterpretation
import io.deepsearch.domain.models.valueobjects.BatchUrlStateId
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.TableDataId
import io.deepsearch.domain.repositories.IWebpageTableInterpretationRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime

import io.deepsearch.domain.services.BatchContentRequest

/**
 * Result of table interpretation containing classification and markdown.
 */
data class TableInterpretationResult(
    val classification: SnippetClassification,
    val markdown: String
)

/**
 * Input for table interpretation batch request preparation.
 */
data class TableInterpretationBatchInput(
    val urlStateId: BatchUrlStateId,
    val tableDataId: TableDataId,
    val tableHtml: String,
    val auxiliaryInfo: String?,
    val boundingBoxes: Map<String, io.deepsearch.domain.browser.IBrowserPage.BoundingBox>
)

/**
 * A pending batch request for table interpretation, bundling the key, request, and hash for caching.
 */
data class PendingTableInterpretationRequest(
    val key: TableKey,
    val request: BatchContentRequest,
    val tableHtmlHash: ByteArray
)

/**
 * Result of preparing table interpretation batch requests.
 */
data class TableInterpretationBatchPreparation(
    /** Map of TableKey to cached markdown */
    val cachedResults: Map<TableKey, String>,
    /** Pending requests for uncached tables (bundled with key and hash) */
    val pendingRequests: List<PendingTableInterpretationRequest>
)

interface ITableInterpretationService {
    suspend fun interpretTable(input: TableInterpretationInput, sessionId: SessionId): TableInterpretationResult
    suspend fun interpretTablesBatch(inputs: List<TableInterpretationInput>, sessionId: SessionId): List<TableInterpretationResult>
    
    // ========== Batch API Methods ==========
    
    /**
     * Prepare batch requests for table interpretation with cache check.
     * 
     * @param tables List of table interpretation inputs
     * @param jobId Batch job ID for request ID generation
     * @return Cached results and batch requests for uncached tables
     */
    suspend fun prepareBatchRequests(
        tables: List<TableInterpretationBatchInput>,
        jobId: Long
    ): TableInterpretationBatchPreparation
    
    /**
     * Parse batch response and return interpretation result.
     * 
     * @param responseText JSON response from batch API
     * @return TableInterpretationResult with classification and markdown
     */
    fun parseBatchResponse(responseText: String): TableInterpretationResult
    
    /**
     * Cache table interpretation result.
     * 
     * @param tableHtmlHash SHA-256 hash of the table HTML
     * @param classification The content classification
     * @param markdown Interpreted markdown to cache
     */
    suspend fun cacheResult(tableHtmlHash: ByteArray, classification: SnippetClassification, markdown: String)
}

class TableInterpretationService(
    private val tableInterpretationAgent: ITableInterpretationAgent,
    private val webpageTableInterpretationRepository: IWebpageTableInterpretationRepository,
    private val tokenUsageService: ILlmTokenUsageService
) : ITableInterpretationService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    // ========== Batch API Methods ==========

    override suspend fun prepareBatchRequests(
        tables: List<TableInterpretationBatchInput>,
        jobId: Long
    ): TableInterpretationBatchPreparation {
        if (tables.isEmpty()) {
            return TableInterpretationBatchPreparation(emptyMap(), emptyList())
        }

        val cachedResults = mutableMapOf<TableKey, String>()
        val pendingRequests = mutableListOf<PendingTableInterpretationRequest>()

        for (table in tables) {
            val key = TableKey(table.urlStateId, table.tableDataId)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(table.tableHtml.toByteArray())
            val tableHash = digest.digest()

            // Check cache
            val existing = webpageTableInterpretationRepository.findByHash(tableHash)
            if (existing != null) {
                cachedResults[key] = existing.markdown
                continue
            }

            // Prepare batch request using agent
            val request = tableInterpretationAgent.prepareBatchRequest(
                requestId = "$jobId-table-interp-${table.urlStateId.value}-${table.tableDataId.value}",
                tableHtml = table.tableHtml,
                auxiliaryInfo = table.auxiliaryInfo ?: "",
                boundingBoxes = table.boundingBoxes
            )
            
            pendingRequests.add(
                PendingTableInterpretationRequest(
                    key = key,
                    request = request,
                    tableHtmlHash = tableHash
                )
            )
        }

        logger.debug("Table interpretation batch: {} cached, {} need processing", cachedResults.size, pendingRequests.size)

        return TableInterpretationBatchPreparation(
            cachedResults = cachedResults,
            pendingRequests = pendingRequests
        )
    }

    override fun parseBatchResponse(responseText: String): TableInterpretationResult {
        val batchResult = tableInterpretationAgent.parseBatchResponse(responseText)
        return TableInterpretationResult(
            classification = batchResult.classification,
            markdown = batchResult.markdown
        )
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun cacheResult(tableHtmlHash: ByteArray, classification: SnippetClassification, markdown: String) {
        webpageTableInterpretationRepository.upsert(
            WebpageTableInterpretation(
                tableDataHash = tableHtmlHash,
                classification = classification,
                markdown = markdown
            )
        )
    }

    // ========== Interactive Mode Methods ==========

    /**
     * Interprets a table using an LLM agent and returns classification and markdown.
     * Results are cached in the repository to avoid repeated calls with the same input.
     * 
     * The LLM verifies if the content is actually a data table. If not (e.g., navigation menu,
     * card grid, etc.), an empty string is returned.
     * 
     * The input now contains pre-computed tableHtml derived from the page snapshot,
     * eliminating the need for browser access during interpretation.
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun interpretTable(input: TableInterpretationInput, sessionId: SessionId): TableInterpretationResult {
        // Create a hash from the pre-computed table HTML
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(input.tableHtml.toByteArray())
        val dataHash = digest.digest()

        val existing = webpageTableInterpretationRepository.findByHash(dataHash)
        if (existing != null) {
            logger.debug("Cache hit for table {}", input.tableIdentification.auxiliaryInfo)
            return TableInterpretationResult(
                classification = existing.classification,
                markdown = existing.markdown
            )
        }

        val agentOutput = tableInterpretationAgent.generate(input)

        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "TableInterpretationAgent",
            modelName = agentOutput.tokenUsage.modelName,
            promptTokens = agentOutput.tokenUsage.promptTokens,
            outputTokens = agentOutput.tokenUsage.outputTokens,
            totalTokens = agentOutput.tokenUsage.totalTokens
        )

        // Log classification result
        logger.debug(
            "Table interpretation for '{}': classification={}", 
            input.tableIdentification.dataId, 
            agentOutput.classification
        )

        webpageTableInterpretationRepository.upsert(
            WebpageTableInterpretation(
                tableDataHash = dataHash,
                classification = agentOutput.classification,
                markdown = agentOutput.markdown
            )
        )

        return TableInterpretationResult(
            classification = agentOutput.classification,
            markdown = agentOutput.markdown
        )
    }

    /**
     * Interprets multiple tables in batch, leveraging caching and batch upsert
     * to reduce concurrent upsert issues.
     * 
     * The LLM verifies if each candidate is actually a data table. Non-tables (e.g., navigation
     * menus, card grids, etc.) will have empty markdown strings.
     * 
     * The inputs now contain pre-computed tableHtml derived from the page snapshot,
     * eliminating the need for browser access during interpretation.
     * 
     * @param inputs List of table interpretation inputs with pre-computed data
     * @param sessionId Query session ID for token tracking
     * @return List of TableInterpretationResult in the same order as inputs
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun interpretTablesBatch(inputs: List<TableInterpretationInput>, sessionId: SessionId): List<TableInterpretationResult> {
        if (inputs.isEmpty()) return emptyList()

        // Initialize result storage
        val results = MutableList<TableInterpretationResult?>(inputs.size) { null }

        // Compute hashes using pre-computed table HTML (no browser access needed)
        val hashes = inputs.map { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(input.tableHtml.toByteArray())
            digest.digest()
        }
        logger.debug("Computing hashes for {} tables using pre-fetched HTML", inputs.size)

        // Batch cache lookup (single DB query instead of N queries)
        val cachedInterpretations = webpageTableInterpretationRepository.findByHashes(hashes)
        val cachedByHash = cachedInterpretations.associateBy { Base64.encode(it.tableDataHash) }
        
        logger.debug("Found {} cached interpretations for {} tables", cachedByHash.size, inputs.size)

        // Populate results from cache and collect uncached entries
        val uncachedEntries = mutableListOf<Triple<Int, TableInterpretationInput, ByteArray>>()
        
        for (index in inputs.indices) {
            val hashKey = Base64.encode(hashes[index])
            val cached = cachedByHash[hashKey]
            if (cached != null) {
                results[index] = TableInterpretationResult(
                    classification = cached.classification,
                    markdown = cached.markdown
                )
            } else {
                uncachedEntries.add(Triple(index, inputs[index], hashes[index]))
            }
        }

        // Generate interpretations for uncached inputs in parallel
        val newInterpretations = coroutineScope {
            uncachedEntries.map { (index, input, hash) ->
                async {
                    val agentOutput = tableInterpretationAgent.generate(input)
                    
                    // Record token usage
                    tokenUsageService.recordTokenUsage(
                        sessionId = sessionId,
                        agentName = "TableInterpretationAgent",
                        modelName = agentOutput.tokenUsage.modelName,
                        promptTokens = agentOutput.tokenUsage.promptTokens,
                        outputTokens = agentOutput.tokenUsage.outputTokens,
                        totalTokens = agentOutput.tokenUsage.totalTokens
                    )
                    
                    logger.debug(
                        "Table interpretation for '{}': classification={}", 
                        input.tableIdentification.dataId,
                        agentOutput.classification
                    )
                    
                    index to WebpageTableInterpretation(
                        tableDataHash = hash,
                        classification = agentOutput.classification,
                        markdown = agentOutput.markdown
                    )
                }
            }.awaitAll()
        }

        // Batch upsert all new interpretations
        if (newInterpretations.isNotEmpty()) {
            webpageTableInterpretationRepository.batchUpsert(
                newInterpretations.map { it.second }
            )
        }

        // Fill remaining results with new interpretations
        for ((index, interpretation) in newInterpretations) {
            results[index] = TableInterpretationResult(
                classification = interpretation.classification,
                markdown = interpretation.markdown
            )
        }

        // All results are now populated
        return results.map { it!! }
    }
}

