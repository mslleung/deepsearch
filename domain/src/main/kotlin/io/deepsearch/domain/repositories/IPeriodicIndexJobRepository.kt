package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.PeriodicIndexJob
import io.deepsearch.domain.models.entities.PeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.UserId

interface IPeriodicIndexJobRepository {
    suspend fun create(job: PeriodicIndexJob): PeriodicIndexJob
    suspend fun update(job: PeriodicIndexJob): PeriodicIndexJob
    suspend fun findById(id: Long): PeriodicIndexJob?
    suspend fun findActiveByBaseUrl(baseUrl: String): PeriodicIndexJob?
    suspend fun listAll(state: PeriodicIndexJobState? = null): List<PeriodicIndexJob>
    suspend fun listByUserId(userId: UserId, state: PeriodicIndexJobState? = null, offset: Int = 0, limit: Int = 10): List<PeriodicIndexJob>
    suspend fun countByUserId(userId: UserId, state: PeriodicIndexJobState? = null): Long
}

