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
     * Search for similar webpages using vector cosine distance.
     * Returns webpages ordered by similarity (most similar first).
     *
     * Only searches within webpages that:
     * - Have the specified URL prefix (to limit search to a specific domain/path)
     * - Were updated after the specified timestamp (to respect cache expiry)
     * - Have non-null embeddings
     *
     * @param queryEmbedding The embedding vector to search with (1536 dimensions)
     * @param urlPrefix The URL prefix to filter by (e.g., "https://example.com")
     * @param minUpdatedAtEpochMs Minimum timestamp in epoch milliseconds (for cache expiry)
     * @param limit Maximum number of results to return
     * @return List of WebpageMarkdown objects, ordered by similarity (most similar first)
     */
    suspend fun searchSimilar(
        queryEmbedding: List<Float>,
        urlPrefix: String,
        minUpdatedAtEpochMs: Long,
        limit: Int
    ): List<WebpageMarkdown>
}

