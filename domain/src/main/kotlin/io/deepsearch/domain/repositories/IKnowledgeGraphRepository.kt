package io.deepsearch.domain.repositories

import io.deepsearch.domain.knowledgegraph.KgEntity
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.knowledgegraph.KgSubgraph
import java.util.UUID

/**
 * Repository for managing the knowledge graph using Apache AGE.
 * Handles entity/relationship storage, retrieval, and incremental updates.
 */
interface IKnowledgeGraphRepository {
    
    // ========================================
    // INDEXING OPERATIONS
    // ========================================
    
    /**
     * Index a document's extracted entities and relationships into the knowledge graph.
     * Performs entity resolution to merge with existing entities and tracks provenance.
     * 
     * @param url The source URL of the document
     * @param extraction The extracted entities and relationships from the document
     */
    suspend fun indexDocument(url: String, extraction: KgExtractionResult)
    
    /**
     * Remove a document from the knowledge graph.
     * Deletes provenance for the URL and garbage collects orphaned entities.
     * 
     * @param url The source URL to remove
     */
    suspend fun removeDocument(url: String)
    
    /**
     * Batch index multiple documents' extractions.
     * More efficient than individual indexDocument calls for batch processing.
     * 
     * @param extractions Map of URL to extraction result
     */
    suspend fun batchIndexDocuments(extractions: Map<String, KgExtractionResult>)
    
    // ========================================
    // QUERY OPERATIONS
    // ========================================
    
    /**
     * Search for entities by semantic similarity to the query embedding.
     * Returns entities ordered by similarity score (most similar first).
     * 
     * @param queryEmbedding The embedding vector of the search query
     * @param limit Maximum number of entities to return
     * @param urlPrefix Optional URL prefix to filter entities by source URL
     * @return List of matching entities ordered by similarity
     */
    suspend fun semanticEntitySearch(
        queryEmbedding: FloatArray,
        limit: Int,
        urlPrefix: String? = null
    ): List<KgEntity>
    
    /**
     * Traverse the graph from a set of entity IDs, expanding N hops.
     * Returns a subgraph containing all reached entities and relationships.
     * 
     * @param entityIds Starting entity IDs for traversal
     * @param maxHops Maximum number of hops to traverse (default 2)
     * @return Subgraph containing entities and relationships
     */
    suspend fun traverseFromEntities(
        entityIds: List<UUID>,
        maxHops: Int = 2
    ): KgSubgraph
    
    /**
     * Execute a Cypher query against the graph and return results.
     * Query is executed with timeout and safety limits.
     * 
     * @param cypherQuery The Cypher query to execute
     * @param timeoutSeconds Maximum execution time (default 5 seconds)
     * @return List of result rows as maps
     */
    suspend fun executeCypher(
        cypherQuery: String,
        timeoutSeconds: Int = 5
    ): List<Map<String, String>>
    
    /**
     * Get a description of the graph schema for text-to-cypher generation.
     * Includes entity types, relationship types, and their counts.
     * 
     * @return Human-readable schema description
     */
    suspend fun getSchemaDescription(): String
    
    /**
     * Check if the knowledge graph has any data for the given URL prefix.
     * Useful to determine if KG queries would be useful.
     * 
     * @param urlPrefix The URL prefix to check
     * @return True if entities exist for this URL prefix
     */
    suspend fun hasDataForUrlPrefix(urlPrefix: String): Boolean
}

