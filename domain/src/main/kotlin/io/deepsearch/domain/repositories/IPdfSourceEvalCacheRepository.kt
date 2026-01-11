package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.PdfSourceEvalCache

/**
 * Repository for caching PDF source evaluation results.
 * 
 * Uses content hash (SHA-256 of query + extractedText) as the lookup key to enable
 * reuse of LLM evaluation results across search sessions.
 */
interface IPdfSourceEvalCacheRepository {
    /**
     * Find a cached evaluation result by content hash.
     * 
     * @param contentHash SHA-256 hash of (query + extractedText)
     * @return Cached evaluation result, or null if not found
     */
    suspend fun findByHash(contentHash: ByteArray): PdfSourceEvalCache?
    
    /**
     * Store or update a cached evaluation result.
     * 
     * @param cache The cache entry to store
     */
    suspend fun upsert(cache: PdfSourceEvalCache)
}
