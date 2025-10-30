package io.deepsearch.presentation.admin.dto

import io.deepsearch.domain.models.entities.PlanTier
import io.deepsearch.domain.models.entities.SubscriptionPlan
import io.deepsearch.domain.models.entities.UserSubscription
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@Serializable
data class AdminSubscriptionPlanDto(
    val planName: String,
    val tier: String,
    val maxSearches: Int,
    val priceUsd: Double
)

fun SubscriptionPlan.toAdminDto(): AdminSubscriptionPlanDto {
    return AdminSubscriptionPlanDto(
        planName = this.planName,
        tier = this.tier.name,
        maxSearches = this.maxSearches,
        priceUsd = this.priceUsd
    )
}

@OptIn(ExperimentalTime::class)
@Serializable
data class AdminUserSubscriptionDto(
    val id: Long,
    val userId: Int,
    val planName: String,
    val tier: String,
    val maxSearches: Int,
    val priceUsd: Double,
    val usedSearches: Int,
    val startDate: Long, // epoch millis
    val expiryDate: Long?, // epoch millis
    val createdAt: Long,
    val updatedAt: Long
)

@OptIn(ExperimentalTime::class)
fun UserSubscription.toAdminDto(): AdminUserSubscriptionDto {
    return AdminUserSubscriptionDto(
        id = this.id!!.value,
        userId = this.userId.value,
        planName = this.planName,
        tier = this.tier.name,
        maxSearches = this.maxSearches,
        priceUsd = this.priceUsd,
        usedSearches = this.usedSearches,
        startDate = this.startDate.toEpochMilliseconds(),
        expiryDate = this.expiryDate?.toEpochMilliseconds(),
        createdAt = this.createdAt.toEpochMilliseconds(),
        updatedAt = this.updatedAt.toEpochMilliseconds()
    )
}

@Serializable
data class CreateUserSubscriptionRequest(
    val userId: Int,
    val planName: String,
    val startDate: Long?, // epoch millis, optional
    val expiryDate: Long? // epoch millis, optional
)

@Serializable
data class UpdateUserSubscriptionRequest(
    val usedSearches: Int?,
    val expiryDate: Long? // epoch millis
)

