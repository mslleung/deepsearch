package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.WebpageTableInterpretation

interface IWebpageTableInterpretationRepository {
    suspend fun upsert(interpretation: WebpageTableInterpretation)
    suspend fun batchUpsert(interpretations: List<WebpageTableInterpretation>)
    suspend fun findByHash(tableDataHash: ByteArray): WebpageTableInterpretation?
    
    /**
     * Batch lookup for multiple table interpretation hashes.
     * Returns interpretations that exist in the cache, in no particular order.
     */
    suspend fun findByHashes(tableDataHashes: List<ByteArray>): List<WebpageTableInterpretation>
}

