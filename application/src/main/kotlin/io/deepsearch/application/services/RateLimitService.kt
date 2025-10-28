package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.ApiKeyRequest
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.repositories.IApiKeyRequestRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

interface IRateLimitService {
    suspend fun checkRateLimit(apiKeyId: ApiKeyId, limitPerMinute: Int): Boolean
    suspend fun recordRequest(apiKeyId: ApiKeyId)
    suspend fun cleanupOldRequests()
}

@OptIn(ExperimentalTime::class)
class RateLimitService(
    private val apiKeyRequestRepository: IApiKeyRequestRepository
) : IRateLimitService {

    override suspend fun checkRateLimit(apiKeyId: ApiKeyId, limitPerMinute: Int): Boolean {
        val now = Clock.System.now()
        val oneMinuteAgo = now - 1.minutes
        
        val requestCount = apiKeyRequestRepository.countRequestsSince(apiKeyId, oneMinuteAgo)
        return requestCount < limitPerMinute
    }

    override suspend fun recordRequest(apiKeyId: ApiKeyId) {
        val now = Clock.System.now()
        val request = ApiKeyRequest(
            apiKeyId = apiKeyId,
            requestedAt = now
        )
        apiKeyRequestRepository.save(request)
    }

    override suspend fun cleanupOldRequests() {
        val now = Clock.System.now()
        val oneDayAgo = now - 1440.minutes // 24 hours
        apiKeyRequestRepository.deleteRequestsBefore(oneDayAgo)
    }
}

