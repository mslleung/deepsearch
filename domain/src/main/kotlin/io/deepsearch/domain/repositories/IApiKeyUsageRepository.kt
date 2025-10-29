package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.ApiKeyUsage
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.UserId
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
interface IApiKeyUsageRepository {
    suspend fun recordUsage(usage: ApiKeyUsage): ApiKeyUsage
    suspend fun countRequestsSince(apiKeyId: ApiKeyId, since: Instant): Long
    suspend fun deleteRequestsBefore(before: Instant): Int
    suspend fun getUsageByDateRange(start: Instant, end: Instant): List<ApiKeyUsage>
    suspend fun getUsageByUserIdAndDateRange(userId: UserId, start: Instant, end: Instant): List<ApiKeyUsage>
    suspend fun getUsageByApiKeyIdAndDateRange(apiKeyId: ApiKeyId, start: Instant, end: Instant): List<ApiKeyUsage>
}

