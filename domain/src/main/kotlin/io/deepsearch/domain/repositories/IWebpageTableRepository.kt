package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageTable

interface IWebpageTableRepository {
    suspend fun upsert(table: WebpageTable)
    suspend fun findByHash(fullPageScreenshotHash: ByteArray): WebpageTable?
}

