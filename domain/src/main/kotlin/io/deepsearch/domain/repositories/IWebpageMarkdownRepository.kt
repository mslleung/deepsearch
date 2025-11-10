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
     * Returns URLs and their cosine distances, ordered by similarity (closest first).
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
     * @return List of (url, distance) pairs, ordered by distance ascending
     */
    suspend fun searchSimilar(
        queryEmbedding: List<Float>,
        urlPrefix: String,
        minUpdatedAtEpochMs: Long,
        limit: Int
    ): List<Pair<String, Float>>
    
    /**
     * Find all URLs with the specified prefix that were updated after the specified timestamp
     * and have non-null embeddings.
     * Used to mark all non-expired cached URLs as "seen" during search.
     * 
     * @param urlPrefix The URL prefix to filter by
     * @param minUpdatedAtEpochMs Minimum timestamp in epoch milliseconds
     * @return List of URLs matching the criteria
     */
    suspend fun findAllUrlsByPrefixWithEmbeddings(
        urlPrefix: String,
        minUpdatedAtEpochMs: Long
    ): List<String>
}

