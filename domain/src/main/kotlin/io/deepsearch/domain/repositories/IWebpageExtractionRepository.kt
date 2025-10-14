package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageExtraction

interface IWebpageExtractionRepository {
    suspend fun upsert(extraction: WebpageExtraction)
    suspend fun findByHash(webpageHtmlHash: ByteArray): WebpageExtraction?
}

