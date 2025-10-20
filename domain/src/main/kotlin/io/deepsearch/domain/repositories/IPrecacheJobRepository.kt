package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.PrecacheJob
import io.deepsearch.domain.models.entities.PrecacheJobState

interface IPrecacheJobRepository {
    suspend fun create(job: PrecacheJob): PrecacheJob
    suspend fun update(job: PrecacheJob): PrecacheJob
    suspend fun findById(id: Long): PrecacheJob?
    suspend fun findActiveByBaseUrl(baseUrl: String): PrecacheJob?
    suspend fun listAll(state: PrecacheJobState? = null): List<PrecacheJob>
}
