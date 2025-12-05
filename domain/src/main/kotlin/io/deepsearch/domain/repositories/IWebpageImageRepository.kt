package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageImage

interface IWebpageImageRepository {
    suspend fun upsert(image: WebpageImage)
    suspend fun batchUpsert(images: List<WebpageImage>)
    suspend fun findByHash(imageBytesHash: ByteArray): WebpageImage?
    
    /**
     * Batch lookup for multiple image hashes.
     * Used when fetching images referenced in an answer.
     */
    suspend fun findByHashes(imageHashes: List<ByteArray>): List<WebpageImage>
}
