package io.deepsearch.domain.repositories

import io.deepsearch.domain.knowledgegraph.KgEntity
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.knowledgegraph.KgSubgraph
import java.util.UUID

/**
 * Pre-computed embeddings for entity names.
 * Used for entity resolution (finding similar entities) and semantic search.
 */
data class EntityEmbeddings(
    private val embeddings: Map<String, List<Float>>
) {
    /** Get embedding for an entity name, or null if not present */
    operator fun get(entityName: String): List<Float>? = embeddings[entityName]
    
    /** Check if embeddings exist for an entity name */
    operator fun contains(entityName: String): Boolean = entityName in embeddings
    
    companion object {
        /** Create from a map of entity names to embedding vectors */
        fun fromMap(map: Map<String, List<Float>>): EntityEmbeddings = EntityEmbeddings(map)
        
        /** Create empty embeddings (for testing or when embeddings not needed) */
        fun empty(): EntityEmbeddings = EntityEmbeddings(emptyMap())
    }
}

/**
 * Repository for managing the knowledge graph using Apache AGE.
 * Handles entity/relationship storage, retrieval, and incremental updates.
 * 
 * Note: Embeddings must be pre-computed by the caller (application layer) 
 * to ensure proper token usage tracking.
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
     * @param embeddings Pre-computed embeddings for entity names (for entity resolution)
     */
    suspend fun indexDocument(
        url: String,
        extraction: KgExtractionResult,
        embeddings: EntityEmbeddings
    )
    
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
     * @param embeddings Pre-computed embeddings for all entity names across all extractions
     */
    suspend fun batchIndexDocuments(
        extractions: Map<String, KgExtractionResult>,
        embeddings: EntityEmbeddings
    )
    
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
     * @param minExtractedAtEpochMs Optional minimum extraction timestamp - only includes entities 
     *        with at least one source extracted at or after this timestamp
     * @return List of matching entities ordered by similarity
     */
    suspend fun semanticEntitySearch(
        queryEmbedding: FloatArray,
        limit: Int,
        urlPrefix: String? = null,
        minExtractedAtEpochMs: Long? = null
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

