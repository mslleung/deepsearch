package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.ApiKey
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.UserId

interface IApiKeyRepository {
    suspend fun save(apiKey: ApiKey): ApiKey
    suspend fun findById(id: ApiKeyId): ApiKey?
    suspend fun findByKeyHash(hash: String): ApiKey?
    suspend fun findByUserId(userId: UserId): List<ApiKey>
    suspend fun delete(id: ApiKeyId): Boolean
    suspend fun update(apiKey: ApiKey): ApiKey
}

