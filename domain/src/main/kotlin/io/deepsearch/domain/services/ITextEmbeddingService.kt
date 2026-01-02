package io.deepsearch.domain.services

import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

/**
 * Result of embedding operation including embeddings and token usage metrics.
 */
data class EmbeddingResult(
    val embeddings: List<List<Float>>,
    val tokenUsage: TokenUsageMetrics
)

/**
 * Result of single query embedding operation including embedding and token usage metrics.
 */
data class QueryEmbeddingResult(
    val embedding: List<Float>,
    val tokenUsage: TokenUsageMetrics
)

/**
 * Service for generating text embeddings using Gemini embedding models.
 * 
 * This service converts text into high-dimensional vectors (embeddings) that can be used
 * for semantic search, similarity comparison, and retrieval-augmented generation (RAG).
 */
interface ITextEmbeddingService {
    /**
     * Generate embeddings for multiple documents.
     * 
     * Use this method when embedding documents that will be stored in a vector database
     * for later retrieval. This uses the RETRIEVAL_DOCUMENT task type, which optimizes
     * the embeddings for being retrieved by search queries.
     *
     * @param texts List of text strings to embed
     * @return EmbeddingResult containing list of embeddings and token usage metrics
     */
    suspend fun embedDocuments(texts: List<String>): EmbeddingResult

    /**
     * Generate an embedding for a search query.
     * 
     * Use this method when embedding a user's search query that will be used to retrieve
     * relevant documents from a vector database. This uses the RETRIEVAL_QUERY task type,
     * which optimizes the embedding for finding semantically similar documents.
     *
     * @param text The query text to embed
     * @return QueryEmbeddingResult containing embedding and token usage metrics
     */
    suspend fun embedQuery(text: String): QueryEmbeddingResult

    /**
     * Generate embeddings for symmetric similarity comparison.
     * 
     * Use this method when comparing texts for semantic similarity where both sides
     * are of the same type (e.g., entity names, short phrases). This uses the 
     * SEMANTIC_SIMILARITY task type, which optimizes embeddings for comparing
     * how similar two pieces of text are in meaning.
     * 
     * Common use cases:
     * - Entity resolution: "Microsoft Corp." vs "Microsoft Corporation"
     * - Duplicate detection: comparing titles or names
     * - Paraphrase detection
     * - Searching for entities in a knowledge graph
     *
     * @param texts List of text strings to embed
     * @return EmbeddingResult containing list of embeddings and token usage metrics
     */
    suspend fun embedForSimilarity(texts: List<String>): EmbeddingResult

    /**
     * Generate a single embedding for symmetric similarity comparison.
     * 
     * Convenience overload for embedding a single text. Delegates to [embedForSimilarity].
     *
     * @param text The text to embed
     * @return QueryEmbeddingResult containing embedding and token usage metrics
     */
    suspend fun embedForSimilarity(text: String): QueryEmbeddingResult {
        val result = embedForSimilarity(listOf(text))
        return QueryEmbeddingResult(
            embedding = result.embeddings.first(),
            tokenUsage = result.tokenUsage
        )
    }
}

