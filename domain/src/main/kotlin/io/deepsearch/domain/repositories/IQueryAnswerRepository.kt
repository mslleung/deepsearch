package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.QueryAnswer

interface IQueryAnswerRepository {
    suspend fun upsert(queryAnswer: QueryAnswer)
    suspend fun findByHash(queryHash: ByteArray): QueryAnswer?
}
