package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.ApiKeyUsage
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.repositories.IApiKeyUsageRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

interface IRateLimitService {
    suspend fun checkRateLimit(apiKeyId: ApiKeyId, limitPerMinute: Int): Boolean
    suspend fun recordUsage(apiKeyId: ApiKeyId)
    suspend fun cleanupOldRequests()
}

@OptIn(ExperimentalTime::class)
class RateLimitService(
    private val apiKeyUsageRepository: IApiKeyUsageRepository
) : IRateLimitService {

    override suspend fun checkRateLimit(apiKeyId: ApiKeyId, limitPerMinute: Int): Boolean {
        val now = Clock.System.now()
        val oneMinuteAgo = now - 1.minutes
        
        val requestCount = apiKeyUsageRepository.countRequestsSince(apiKeyId, oneMinuteAgo)
        return requestCount < limitPerMinute
    }

    override suspend fun recordUsage(apiKeyId: ApiKeyId) {
        val now = Clock.System.now()
        val usage = ApiKeyUsage(
            apiKeyId = apiKeyId,
            requestedAt = now
        )
        apiKeyUsageRepository.recordUsage(usage)
    }

    override suspend fun cleanupOldRequests() {
        val now = Clock.System.now()
        val oneDayAgo = now - 1440.minutes // 24 hours
        apiKeyUsageRepository.deleteRequestsBefore(oneDayAgo)
    }
}

