package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageMarkdown

interface IWebpageMarkdownRepository {
    suspend fun findByUrl(url: String): WebpageMarkdown?
    suspend fun upsert(webpage: WebpageMarkdown)
    suspend fun listByDomainPrefix(prefix: String, offset: Int, limit: Int): List<WebpageMarkdown>
    suspend fun countByDomainPrefix(prefix: String): Long
    suspend fun searchByUrl(query: String, offset: Int, limit: Int): List<WebpageMarkdown>
    suspend fun countSearchByUrl(query: String): Long
    
    /**
     * Search for similar webpages using hybrid search with Reciprocal Rank Fusion (RRF).
     * Combines keyword-based full-text search with semantic vector similarity search.
     * Returns webpages ordered by combined relevance score (most relevant first).
     *
     * Only searches within webpages that:
     * - Have the specified URL prefix (to limit search to a specific domain/path)
     * - Were updated after the specified timestamp (if minUpdatedAtEpochMs is non-null)
     * - Have non-null embeddings and markdown content
     *
     * @param textQuery The text query for both keyword search and embedding generation
     * @param queryEmbedding The embedding vector for semantic search (1536 dimensions)
     * @param urlPrefix The URL prefix to filter by (e.g., "https://example.com")
     * @param minUpdatedAtEpochMs Minimum timestamp in epoch milliseconds (null means no timestamp filtering)
     * @param limit Maximum number of results to return
     * @return List of WebpageMarkdown objects, ordered by RRF combined score (most relevant first)
     */
    suspend fun searchHybrid(
        textQuery: String,
        queryEmbedding: List<Float>,
        urlPrefix: String,
        minUpdatedAtEpochMs: Long?,
        limit: Int
    ): List<WebpageMarkdown>
}

