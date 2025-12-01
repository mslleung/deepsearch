package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.entities.SubscriptionPlan
import io.deepsearch.domain.models.entities.UserSubscription
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@Serializable
data class SubscriptionPlanDto(
    val id: Int,
    val name: String,
    val tier: String,
    val maxSearches: Int,
    val priceUsd: Double
)

@Serializable
data class UserSubscriptionDto(
    val id: Long,
    val planName: String,
    val tier: String,
    val maxSearches: Int,
    val priceUsd: Double,
    val usedSearches: Int,
    val startDate: String,
    val expiryDate: String?
)

@Serializable
data class CurrentUsageResponse(
    val usableSubscription: UserSubscriptionDto?,
    val allActiveSubscriptions: List<UserSubscriptionDto>,
    val totalUsed: Int,
    val totalAvailable: Int,
    val hasRemainingSearches: Boolean
)

@Serializable
data class UsageStatsResponse(
    val dailyUsage: Map<String, Int>,
    val totalUsage: Int,
    val periodDays: Int
)

@Serializable
data class ApiKeyUsageStatsResponse(
    val apiKeyId: Int,
    val apiKeyName: String,
    val dailyUsage: Map<String, Int>,
    val totalUsage: Int,
    val periodDays: Int
)

@Serializable
data class UpgradePlanRequest(
    val planName: String
)

@Serializable
data class UpgradePlanResponse(
    val checkoutUrl: String,
    val sessionId: String
)

fun SubscriptionPlan.toDto(): SubscriptionPlanDto {
    return SubscriptionPlanDto(
        id = this.ordinal,
        name = this.planName,
        tier = this.tier.name,
        maxSearches = this.maxSearches,
        priceUsd = this.priceUsd
    )
}

@OptIn(ExperimentalTime::class)
fun UserSubscription.toDto(): UserSubscriptionDto {
    return UserSubscriptionDto(
        id = this.id!!.value,
        planName = this.planName,
        tier = this.tier.name,
        maxSearches = this.maxSearches,
        priceUsd = this.priceUsd,
        usedSearches = this.usedSearches,
        startDate = this.startDate.toString(),
        expiryDate = this.expiryDate?.toString()
    )
}

