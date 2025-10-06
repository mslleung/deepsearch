package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageImage

interface IWebpageImageRepository {
    suspend fun upsert(image: WebpageImage)
    suspend fun findByHash(imageBytesHash: ByteArray): WebpageImage?
}
