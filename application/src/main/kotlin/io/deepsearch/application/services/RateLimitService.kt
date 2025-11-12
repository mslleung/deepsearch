package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.repositories.IQuerySessionRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

interface IRateLimitService {
    suspend fun checkRateLimit(apiKeyId: ApiKeyId, limitPerMinute: Int): Boolean
}

@OptIn(ExperimentalTime::class)
class RateLimitService(
    private val querySessionRepository: IQuerySessionRepository
) : IRateLimitService {

    override suspend fun checkRateLimit(apiKeyId: ApiKeyId, limitPerMinute: Int): Boolean {
        val now = Clock.System.now()
        val oneMinuteAgo = now - 1.minutes
        
        val sessionCount = querySessionRepository.countSessionsSince(apiKeyId, oneMinuteAgo)
        return sessionCount < limitPerMinute
    }
}

