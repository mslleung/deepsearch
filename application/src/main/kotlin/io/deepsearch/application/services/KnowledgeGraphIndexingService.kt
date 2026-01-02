package io.deepsearch.application.services

import io.deepsearch.domain.agents.EntityExtractionInput
import io.deepsearch.domain.agents.IEntityExtractionAgent
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.EntityEmbeddings
import io.deepsearch.domain.repositories.IKnowledgeGraphRepository
import io.deepsearch.domain.services.BatchContentRequest
import io.deepsearch.domain.services.ITextEmbeddingService
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
     * @param sessionId Optional session ID for token tracking (if null, tokens are not tracked)
     */
    suspend fun processBatchResults(
        results: Map<String, KgExtractionResult>,
        sessionId: SessionId? = null
    )
}

class KnowledgeGraphIndexingService(
    private val entityExtractionAgent: IEntityExtractionAgent,
    private val knowledgeGraphRepository: IKnowledgeGraphRepository,
    private val applicationScope: IApplicationCoroutineScope,
    private val tokenUsageService: ILlmTokenUsageService,
    private val textEmbeddingService: ITextEmbeddingService
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

                // Record token usage for entity extraction
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

                // Generate embeddings for entities with token tracking
                val entityNames = output.extraction.entities.map { it.name }
                val embeddingResult = textEmbeddingService.embedForSimilarity(entityNames)
                val embeddings = EntityEmbeddings.fromMap(
                    entityNames.zip(embeddingResult.embeddings).toMap()
                )

                // Record token usage for entity embeddings
                tokenUsageService.recordTokenUsage(
                    sessionId = sessionId,
                    agentName = "KnowledgeGraphIndexingService.embedForSimilarity",
                    modelName = embeddingResult.tokenUsage.modelName,
                    promptTokens = embeddingResult.tokenUsage.promptTokens,
                    outputTokens = embeddingResult.tokenUsage.outputTokens,
                    totalTokens = embeddingResult.tokenUsage.totalTokens
                )

                // Index into knowledge graph with pre-computed embeddings
                knowledgeGraphRepository.indexDocument(url, output.extraction, embeddings)

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

    override suspend fun processBatchResults(
        results: Map<String, KgExtractionResult>,
        sessionId: SessionId?
    ) {
        logger.info("Processing {} batch KG extraction results", results.size)

        // Filter out empty extractions
        val nonEmptyResults = results.filterValues { !it.isEmpty() }
        if (nonEmptyResults.isEmpty()) {
            logger.debug("No non-empty extractions to process")
            return
        }

        // Collect all unique entity names for batch embedding
        val allEntityNames = nonEmptyResults.values
            .flatMap { it.entities }
            .map { it.name }
            .distinct()

        // Generate embeddings for all entities in a single batch
        val embeddings = if (allEntityNames.isNotEmpty()) {
            val embeddingResult = textEmbeddingService.embedForSimilarity(allEntityNames)
            
            // Record token usage if sessionId is provided
            if (sessionId != null) {
                tokenUsageService.recordTokenUsage(
                    sessionId = sessionId,
                    agentName = "KnowledgeGraphIndexingService.batchEmbedForSimilarity",
                    modelName = embeddingResult.tokenUsage.modelName,
                    promptTokens = embeddingResult.tokenUsage.promptTokens,
                    outputTokens = embeddingResult.tokenUsage.outputTokens,
                    totalTokens = embeddingResult.tokenUsage.totalTokens
                )
            }
            
            EntityEmbeddings.fromMap(allEntityNames.zip(embeddingResult.embeddings).toMap())
        } else {
            EntityEmbeddings.empty()
        }

        // Use the batch indexing method with pre-computed embeddings
        try {
            knowledgeGraphRepository.batchIndexDocuments(nonEmptyResults, embeddings)
            
            val entityCount = nonEmptyResults.values.sumOf { it.entities.size }
            val relationshipCount = nonEmptyResults.values.sumOf { it.relationships.size }
            
            logger.info(
                "Completed processing batch KG results: {} pages, {} entities, {} relationships",
                nonEmptyResults.size, entityCount, relationshipCount
            )
        } catch (e: Exception) {
            logger.error("Failed to batch index KG extractions: {}", e.message, e)
        }
    }
}

