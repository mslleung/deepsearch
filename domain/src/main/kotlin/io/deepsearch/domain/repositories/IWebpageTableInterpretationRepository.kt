package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageTableInterpretation

interface IWebpageTableInterpretationRepository {
    suspend fun upsert(interpretation: WebpageTableInterpretation)
    suspend fun findByHash(tableDataHash: ByteArray): WebpageTableInterpretation?
}

