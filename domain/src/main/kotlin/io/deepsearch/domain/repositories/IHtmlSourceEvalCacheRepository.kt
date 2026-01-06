package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.HtmlSourceEvalCache

/**
 * Repository for caching HTML source evaluation results.
 * 
 * Uses content hash (SHA-256 of query + HTML) as the lookup key to enable
 * reuse of LLM evaluation results across search sessions.
 */
interface IHtmlSourceEvalCacheRepository {
    /**
     * Find a cached evaluation result by content hash.
     * 
     * @param contentHash SHA-256 hash of (query + cleanedHtml)
     * @return Cached evaluation result, or null if not found
     */
    suspend fun findByHash(contentHash: ByteArray): HtmlSourceEvalCache?
    
    /**
     * Store or update a cached evaluation result.
     * 
     * @param cache The cache entry to store
     */
    suspend fun upsert(cache: HtmlSourceEvalCache)
}

