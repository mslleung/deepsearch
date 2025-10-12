package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageSemanticElement

interface IWebpageNavigationElementRepository {
    suspend fun findByHash(pageHash: ByteArray): WebpageSemanticElement?
    suspend fun upsert(webpageSemanticElement: WebpageSemanticElement)
}

