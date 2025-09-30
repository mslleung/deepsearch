package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpagePopup

interface IWebpagePopupRepository {
    suspend fun findByHash(pageHash: ByteArray): WebpagePopup?
    suspend fun upsert(webpagePopup: WebpagePopup)
}
