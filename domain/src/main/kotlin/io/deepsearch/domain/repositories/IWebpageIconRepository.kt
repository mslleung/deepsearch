package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageIconRecord

interface IWebpageIconRepository {
    suspend fun upsert(pageUrl: String, icon: WebpageIconRecord)
    suspend fun findByUrlAndSelector(pageUrl: String, selector: String): List<WebpageIconRecord>
    suspend fun findByUrlAndHash(pageUrl: String, imageBytesHash: String): WebpageIconRecord?
    suspend fun listByUrl(pageUrl: String): List<WebpageIconRecord>
}