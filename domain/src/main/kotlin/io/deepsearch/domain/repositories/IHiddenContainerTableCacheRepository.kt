package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.HiddenContainerTableCache

/**
 * Repository for caching hidden container table detection results.
 * 
 * Hidden containers (accordions, tabs, collapsed sections) are detected via
 * HTML-based LLM analysis. Since the same HTML structure often appears multiple
 * times (e.g., repeated accordion items), caching provides significant savings.
 */
interface IHiddenContainerTableCacheRepository {
    /**
     * Find a cached result by structural HTML hash.
     * 
     * @param structuralHtmlHash SHA-256 hash of HTML with data-ds-id stripped
     * @return Cached result if found, null otherwise
     */
    suspend fun findByHash(structuralHtmlHash: ByteArray): HiddenContainerTableCache?
    
    /**
     * Batch lookup for multiple structural HTML hashes.
     * 
     * @param hashes List of SHA-256 hashes to look up
     * @return List of cached results that exist (may be fewer than input)
     */
    suspend fun findByHashes(hashes: List<ByteArray>): List<HiddenContainerTableCache>
    
    /**
     * Save or update a cache entry.
     */
    suspend fun upsert(cache: HiddenContainerTableCache)
    
    /**
     * Batch save or update cache entries.
     */
    suspend fun batchUpsert(caches: List<HiddenContainerTableCache>)
}
