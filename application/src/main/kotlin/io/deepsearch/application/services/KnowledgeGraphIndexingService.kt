package io.deepsearch.application.services

import io.deepsearch.domain.agents.EntityExtractionInput
import io.deepsearch.domain.agents.IEntityExtractionAgent
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.IKnowledgeGraphRepository
import io.deepsearch.domain.services.BatchContentRequest
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Service for managing knowledge graph indexing.
 * 
 * Handles entity extraction and graph storage for both interactive 
 * (fire-and-forget) and batch modes.
 */
interface IKnowledgeGraphIndexingService {
    /**
     * Index a document to the knowledge graph asynchronously (fire-and-forget).
     * Used by interactive mode.
     * 
     * @param url The URL of the document (used as provenance)
     * @param markdown The markdown content to extract entities from
     * @param sessionId Session ID for token tracking
     */
    fun indexAsync(
        url: String,
        markdown: String,
        sessionId: SessionId
    )

    /**
     * Prepare a batch content request for entity extraction.
     * Used by batch mode to collect requests before submission.
     * 
     * @param requestId Unique identifier for this request
     * @param markdown The markdown content to extract entities from
     * @param sourceUrl The source URL for provenance
     * @return BatchContentRequest for the Gemini Batch API
     */
    fun prepareBatchRequest(
        requestId: String,
        markdown: String,
        sourceUrl: String
    ): BatchContentRequest

    /**
     * Parse a batch response into extraction results.
     * 
     * @param responseText The JSON response from the batch API
     * @return Extracted entities and relationships
     */
    fun parseBatchResponse(responseText: String): KgExtractionResult

    /**
     * Process batch extraction results and store them in the knowledge graph.
     * Called after batch API returns results.
     * 
     * @param results Map of URL to extraction result
     */
    suspend fun processBatchResults(results: Map<String, KgExtractionResult>)
}

class KnowledgeGraphIndexingService(
    private val entityExtractionAgent: IEntityExtractionAgent,
    private val knowledgeGraphRepository: IKnowledgeGraphRepository,
    private val applicationScope: IApplicationCoroutineScope,
    private val tokenUsageService: ILlmTokenUsageService
) : IKnowledgeGraphIndexingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun indexAsync(
        url: String,
        markdown: String,
        sessionId: SessionId
    ) {
        // Launch in application scope (fire-and-forget)
        applicationScope.scope.launch {
            try {
                logger.debug("KG indexing starting for URL: {}", url)

                // Extract entities and relationships
                val output = entityExtractionAgent.generate(
                    EntityExtractionInput(markdown = markdown, sourceUrl = url)
                )

                // Record token usage
                tokenUsageService.recordTokenUsage(
                    sessionId = sessionId,
                    agentName = "EntityExtractionAgent",
                    modelName = output.tokenUsage.modelName,
                    promptTokens = output.tokenUsage.promptTokens,
                    outputTokens = output.tokenUsage.outputTokens,
                    totalTokens = output.tokenUsage.totalTokens
                )

                if (output.extraction.isEmpty()) {
                    logger.debug("No entities extracted from URL: {}", url)
                    return@launch
                }

                // Index into knowledge graph
                knowledgeGraphRepository.indexDocument(url, output.extraction)

                logger.debug(
                    "KG indexed {} entities and {} relationships from URL: {}",
                    output.extraction.entities.size,
                    output.extraction.relationships.size,
                    url
                )
            } catch (e: Exception) {
                logger.error("Failed to index to KG for URL {}: {}", url, e.message, e)
            }
        }
    }

    override fun prepareBatchRequest(
        requestId: String,
        markdown: String,
        sourceUrl: String
    ): BatchContentRequest {
        return entityExtractionAgent.prepareBatchRequest(requestId, markdown, sourceUrl)
    }

    override fun parseBatchResponse(responseText: String): KgExtractionResult {
        return entityExtractionAgent.parseBatchResponse(responseText)
    }

    override suspend fun processBatchResults(results: Map<String, KgExtractionResult>) {
        logger.info("Processing {} batch KG extraction results", results.size)

        var successCount = 0
        var entityCount = 0
        var relationshipCount = 0

        results.forEach { (url, extraction) ->
            try {
                if (extraction.isEmpty()) {
                    logger.debug("No entities extracted from batch for URL: {}", url)
                    return@forEach
                }

                knowledgeGraphRepository.indexDocument(url, extraction)

                successCount++
                entityCount += extraction.entities.size
                relationshipCount += extraction.relationships.size

                logger.debug(
                    "Indexed {} entities and {} relationships from batch for URL: {}",
                    extraction.entities.size, extraction.relationships.size, url
                )
            } catch (e: Exception) {
                logger.warn("Failed to store batch KG extraction for URL {}: {}", url, e.message)
            }
        }

        logger.info(
            "Completed processing batch KG results: {} pages, {} entities, {} relationships",
            successCount, entityCount, relationshipCount
        )
    }
}

