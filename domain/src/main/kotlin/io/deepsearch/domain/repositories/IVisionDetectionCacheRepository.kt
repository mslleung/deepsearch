package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.VisionDetectionCache

/**
 * Repository for caching vision-based detection results.
 * 
 * Vision detection uses screenshot + HTML to identify semantic elements
 * (header, footer, nav, etc.) and visible tables. The cache stores the
 * raw vision LLM response with normalized bounding boxes that can be
 * remapped to DOM elements on subsequent page loads.
 */
interface IVisionDetectionCacheRepository {
    /**
     * Find a cached result by content hash.
     * 
     * @param contentHash SHA-256 hash of screenshot bytes + structural HTML
     * @return Cached result if found, null otherwise
     */
    suspend fun findByHash(contentHash: ByteArray): VisionDetectionCache?
    
    /**
     * Save or update a cache entry.
     */
    suspend fun upsert(cache: VisionDetectionCache)
}
