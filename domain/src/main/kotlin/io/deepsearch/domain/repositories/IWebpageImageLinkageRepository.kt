package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageImageLinkage

/**
 * Repository for managing URL-to-image linkages.
 * Tracks which images are present on which URLs.
 */
interface IWebpageImageLinkageRepository {
    
    /**
     * Upsert linkages for a URL with the given image hashes.
     * - Creates new linkages for images not previously linked to this URL
     * - Updates existing linkages to mark them as active
     * - Marks linkages for images NOT in the provided list as inactive
     */
    suspend fun upsertLinkages(url: String, imageHashes: List<ByteArray>)
    
    /**
     * Find all active image hashes for a given URL.
     */
    suspend fun findActiveByUrl(url: String): List<ByteArray>
    
    /**
     * Find linkage by URL and image hash.
     */
    suspend fun findByUrlAndHash(url: String, imageBytesHash: ByteArray): WebpageImageLinkage?
}


