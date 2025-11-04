package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageIcon

interface IWebpageIconRepository {
    suspend fun upsert(icon: WebpageIcon)
    suspend fun batchUpsert(icons: List<WebpageIcon>)
    suspend fun findByHash(imageBytesHash: ByteArray): WebpageIcon?
}