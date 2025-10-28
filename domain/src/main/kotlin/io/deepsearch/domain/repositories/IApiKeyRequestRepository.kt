package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.ApiKeyRequest
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface IApiKeyRequestRepository {
    suspend fun save(request: ApiKeyRequest): ApiKeyRequest
    @OptIn(ExperimentalTime::class)
    suspend fun countRequestsSince(apiKeyId: ApiKeyId, since: Instant): Long
    @OptIn(ExperimentalTime::class)
    suspend fun deleteRequestsBefore(before: Instant): Int
}

