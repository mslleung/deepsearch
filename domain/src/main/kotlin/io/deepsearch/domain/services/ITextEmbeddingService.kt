package io.deepsearch.domain.services

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
     * @param sessionId Optional query session ID for tracking token usage
     * @return List of embeddings, where each embedding is a list of 1536 float values
     */
    suspend fun embedDocuments(texts: List<String>, sessionId: String? = null): List<List<Float>>

    /**
     * Generate an embedding for a search query.
     * 
     * Use this method when embedding a user's search query that will be used to retrieve
     * relevant documents from a vector database. This uses the RETRIEVAL_QUERY task type,
     * which optimizes the embedding for finding semantically similar documents.
     *
     * @param text The query text to embed
     * @param sessionId Optional query session ID for tracking token usage
     * @return An embedding as a list of 1536 float values
     */
    suspend fun embedQuery(text: String, sessionId: String? = null): List<Float>
}

