package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageNavigationElement

interface IWebpageNavigationElementRepository {
    suspend fun findByHash(pageHash: ByteArray): WebpageNavigationElement?
    suspend fun upsert(webpageNavigationElement: WebpageNavigationElement)
}

