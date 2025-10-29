package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.ApiKeyUsage
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IApiKeyUsageRepository
import io.deepsearch.domain.repositories.IUserSubscriptionRepository
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface IUsageService {
    suspend fun getUserUsageStats(userId: UserId, days: Int): UsageStats
    suspend fun getApiKeyUsageStats(apiKeyId: ApiKeyId, days: Int): ApiKeyUsageStats
    suspend fun getActiveSubscriptionUsage(userId: UserId): SubscriptionUsageSummary
}

data class UsageStats(
    val dailyUsage: Map<String, Int>, // Date (YYYY-MM-DD) to count
    val totalUsage: Int
)

data class ApiKeyUsageStats(
    val apiKeyId: ApiKeyId,
    val dailyUsage: Map<String, Int>,
    val totalUsage: Int
)

data class SubscriptionUsageSummary(
    val totalUsed: Int,
    val totalAvailable: Int,
    val subscriptions: List<SubscriptionUsageDetail>
)

@OptIn(ExperimentalTime::class)
data class SubscriptionUsageDetail(
    val subscriptionId: Long,
    val planName: String,
    val usedSearches: Int,
    val maxSearches: Int,
    val expiryDate: Instant?
)

@OptIn(ExperimentalTime::class)
class UsageService(
    private val apiKeyUsageRepository: IApiKeyUsageRepository,
    private val userSubscriptionRepository: IUserSubscriptionRepository,
    private val subscriptionPlanService: IUserSubscriptionService
) : IUsageService {

    override suspend fun getUserUsageStats(userId: UserId, days: Int): UsageStats {
        val now = Clock.System.now()
        val startDate = now - days.days

        val usages = apiKeyUsageRepository.getUsageByUserIdAndDateRange(userId, startDate, now)

        return aggregateUsageByDay(usages)
    }

    override suspend fun getApiKeyUsageStats(apiKeyId: ApiKeyId, days: Int): ApiKeyUsageStats {
        val now = Clock.System.now()
        val startDate = now - days.days

        val usages = apiKeyUsageRepository.getUsageByApiKeyIdAndDateRange(apiKeyId, startDate, now)
        val stats = aggregateUsageByDay(usages)

        return ApiKeyUsageStats(
            apiKeyId = apiKeyId,
            dailyUsage = stats.dailyUsage,
            totalUsage = stats.totalUsage
        )
    }

    override suspend fun getActiveSubscriptionUsage(userId: UserId): SubscriptionUsageSummary {
        val activeSubscriptions = subscriptionPlanService.getUserAllActiveSubscriptions(userId)

        val subscriptionDetails = activeSubscriptions.map { subscription ->
            SubscriptionUsageDetail(
                subscriptionId = subscription.id!!.value,
                planName = subscription.planName,
                usedSearches = subscription.usedSearches,
                maxSearches = subscription.maxSearches,
                expiryDate = subscription.expiryDate
            )
        }

        val totalUsed = subscriptionDetails.sumOf { it.usedSearches }
        val totalAvailable = subscriptionDetails.sumOf { it.maxSearches }

        return SubscriptionUsageSummary(
            totalUsed = totalUsed,
            totalAvailable = totalAvailable,
            subscriptions = subscriptionDetails
        )
    }

    private fun aggregateUsageByDay(usages: List<ApiKeyUsage>): UsageStats {
        val dailyUsageMap = mutableMapOf<String, Int>()

        usages.forEach { usage ->
            val dateKey = formatDate(usage.requestedAt)
            dailyUsageMap[dateKey] = dailyUsageMap.getOrDefault(dateKey, 0) + 1
        }

        return UsageStats(
            dailyUsage = dailyUsageMap,
            totalUsage = usages.size
        )
    }

    private fun formatDate(instant: Instant): String {
        // kotlin standard time instant does not allow date operation, so we have to cast to kotlinx datetime instant
        val kotlinxInstant = kotlinx.datetime.Instant.fromEpochMilliseconds(instant.toEpochMilliseconds())
        val datetimeInUtc: LocalDateTime = kotlinxInstant.toLocalDateTime(TimeZone.UTC)

        // Format as YYYY-MM-DD
        return datetimeInUtc.format(LocalDateTime.Format {
            year()
            char('-')
            monthNumber()
            char('-')
            dayOfMonth()
        })
    }
}

