package io.deepsearch.application.services

import io.deepsearch.domain.agents.ITextToCypherAgent
import io.deepsearch.domain.agents.TextToCypherInput
import io.deepsearch.domain.knowledgegraph.KgHybridRetrievalResult
import io.deepsearch.domain.knowledgegraph.KgSubgraph
import io.deepsearch.domain.knowledgegraph.RetrievalSources
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.IKnowledgeGraphRepository
import io.deepsearch.domain.services.ITextEmbeddingService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Service for hybrid retrieval from the knowledge graph.
 * Combines semantic entity search, graph traversal, and text-to-cypher in parallel.
 */
interface IKgHybridRetrievalService {
    
    /**
     * Retrieve relevant information from the knowledge graph using multiple strategies in parallel.
     *
     * @param query The user's natural language query
     * @param baseUrl Base URL to filter results by (optional, uses URL prefix matching)
     * @param maxCacheAge Cache age filter for source URLs (optional)
     * @param sessionId Session ID for token tracking
     * @return Combined retrieval result from all strategies
     */
    suspend fun retrieve(
        query: String,
        baseUrl: String?,
        maxCacheAge: Long?,
        sessionId: SessionId
    ): KgHybridRetrievalResult
    
    /**
     * Check if the knowledge graph has data for a given URL prefix.
     *
     * @param urlPrefix URL prefix to check
     * @return True if entities exist for this URL prefix
     */
    suspend fun hasDataForUrlPrefix(urlPrefix: String): Boolean
}

/**
 * Implementation of KG hybrid retrieval service.
 * Runs semantic search + graph traversal and text-to-cypher in parallel.
 */
@OptIn(ExperimentalTime::class)
class KgHybridRetrievalService(
    private val knowledgeGraphRepository: IKnowledgeGraphRepository,
    private val textToCypherAgent: ITextToCypherAgent,
    private val textEmbeddingService: ITextEmbeddingService,
    private val tokenUsageService: ILlmTokenUsageService
) : IKgHybridRetrievalService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    companion object {
        private const val SEMANTIC_SEARCH_LIMIT = 10
        private const val GRAPH_TRAVERSAL_HOPS = 2
    }

    override suspend fun retrieve(
        query: String,
        baseUrl: String?,
        maxCacheAge: Long?,
        sessionId: SessionId
    ): KgHybridRetrievalResult {
        logger.debug("KG hybrid retrieval starting for query: '{}', baseUrl: {}", query, baseUrl)
        
        return coroutineScope {
            // Run both strategies in parallel
            val graphResultDeferred = async {
                performSemanticGraphRetrieval(query, baseUrl, maxCacheAge, sessionId)
            }
            
            val cypherResultDeferred = async {
                performTextToCypher(query, sessionId)
            }
            
            // Wait for both to complete
            val (subgraph, graphUsed) = graphResultDeferred.await()
            val (cypherResults, cypherQuery, cypherUsed) = cypherResultDeferred.await()
            
            logger.debug(
                "KG hybrid retrieval complete: graph={} entities, cypher={} results",
                subgraph?.entities?.size ?: 0,
                cypherResults?.size ?: 0
            )
            
            KgHybridRetrievalResult(
                subgraph = subgraph,
                cypherResults = cypherResults,
                sources = RetrievalSources(
                    hybridSearchUsed = false, // This is KG-only retrieval
                    graphTraversalUsed = graphUsed,
                    textToCypherUsed = cypherUsed,
                    textToCypherQuery = cypherQuery
                )
            )
        }
    }

    override suspend fun hasDataForUrlPrefix(urlPrefix: String): Boolean {
        return knowledgeGraphRepository.hasDataForUrlPrefix(urlPrefix)
    }

    /**
     * Perform semantic entity search followed by graph traversal.
     * Returns (subgraph, wasUsed)
     */
    private suspend fun performSemanticGraphRetrieval(
        query: String,
        urlPrefix: String?,
        maxCacheAge: Long?,
        sessionId: SessionId
    ): Pair<KgSubgraph?, Boolean> {
        return try {
            // Generate query embedding using SEMANTIC_SIMILARITY task type
            // This matches the task type used when storing entity embeddings
            val embeddingResult = textEmbeddingService.embedForSimilarity(query)
            
            // Record token usage for embedding
            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "KgHybridRetrievalService.embedForSimilarity",
                modelName = embeddingResult.tokenUsage.modelName,
                promptTokens = embeddingResult.tokenUsage.promptTokens,
                outputTokens = embeddingResult.tokenUsage.outputTokens,
                totalTokens = embeddingResult.tokenUsage.totalTokens
            )
            
            // Calculate minimum extraction timestamp for cache age filtering
            val minExtractedAtEpochMs = if (maxCacheAge != null) {
                val timestamp = Clock.System.now().toEpochMilliseconds() - maxCacheAge
                logger.debug("KG semantic search: filtering entities extracted after {}", timestamp)
                timestamp
            } else {
                null
            }
            
            // Semantic entity search
            val entities = knowledgeGraphRepository.semanticEntitySearch(
                queryEmbedding = embeddingResult.embedding.toFloatArray(),
                limit = SEMANTIC_SEARCH_LIMIT,
                urlPrefix = urlPrefix,
                minExtractedAtEpochMs = minExtractedAtEpochMs
            )
            
            if (entities.isEmpty()) {
                logger.debug("No entities found in semantic search")
                return Pair(null, false)
            }
            
            logger.debug("Found {} entities in semantic search, traversing graph", entities.size)
            
            // Get entity IDs for traversal
            val entityIds = entities.mapNotNull { 
                try {
                    UUID.fromString(it.id)
                } catch (e: Exception) {
                    null
                }
            }
            
            // Traverse graph from found entities
            val subgraph = knowledgeGraphRepository.traverseFromEntities(
                entityIds = entityIds,
                maxHops = GRAPH_TRAVERSAL_HOPS
            )
            
            logger.debug(
                "Graph traversal: {} entities, {} relationships",
                subgraph.entities.size,
                subgraph.relationships.size
            )
            
            Pair(subgraph, true)
        } catch (e: Exception) {
            logger.error("Semantic graph retrieval failed: {}", e.message, e)
            Pair(null, false)
        }
    }

    /**
     * Perform text-to-cypher query generation and execution.
     * Returns (results, cypherQuery, wasUsed)
     */
    private suspend fun performTextToCypher(
        query: String,
        sessionId: SessionId
    ): Triple<List<Map<String, String>>?, String?, Boolean> {
        return try {
            // Get schema description for the agent
            val schemaDescription = knowledgeGraphRepository.getSchemaDescription()
            
            if (schemaDescription.isBlank()) {
                logger.debug("Empty schema description, skipping text-to-cypher")
                return Triple(null, null, false)
            }
            
            // Generate Cypher query
            val cypherOutput = textToCypherAgent.generate(
                TextToCypherInput(
                    query = query,
                    schemaDescription = schemaDescription
                )
            )
            
            // Record token usage
            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "TextToCypherAgent",
                modelName = cypherOutput.tokenUsage.modelName,
                promptTokens = cypherOutput.tokenUsage.promptTokens,
                outputTokens = cypherOutput.tokenUsage.outputTokens,
                totalTokens = cypherOutput.tokenUsage.totalTokens
            )
            
            if (!cypherOutput.isValid || cypherOutput.cypherQuery.isBlank()) {
                logger.debug("Text-to-cypher not applicable: {}", cypherOutput.explanation)
                return Triple(null, null, false)
            }
            
            logger.debug("Generated Cypher query: {}", cypherOutput.cypherQuery)
            
            // Execute the query
            val results = knowledgeGraphRepository.executeCypher(
                cypherQuery = cypherOutput.cypherQuery,
                timeoutSeconds = 5
            )
            
            logger.debug("Cypher query returned {} results", results.size)
            
            Triple(results.ifEmpty { null }, cypherOutput.cypherQuery, true)
        } catch (e: Exception) {
            logger.error("Text-to-cypher retrieval failed: {}", e.message, e)
            Triple(null, null, false)
        }
    }
}

