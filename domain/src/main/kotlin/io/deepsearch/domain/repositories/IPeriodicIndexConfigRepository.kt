package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.PeriodicIndexConfig
import io.deepsearch.domain.models.valueobjects.UserId

interface IPeriodicIndexConfigRepository {
    suspend fun create(config: PeriodicIndexConfig): PeriodicIndexConfig
    suspend fun update(config: PeriodicIndexConfig): PeriodicIndexConfig
    suspend fun findById(id: Long): PeriodicIndexConfig?
    suspend fun findAllByUserId(userId: UserId): List<PeriodicIndexConfig>
    suspend fun countByUserId(userId: UserId): Int
    suspend fun findEnabledConfigs(): List<PeriodicIndexConfig>
    suspend fun delete(config: PeriodicIndexConfig)
}
