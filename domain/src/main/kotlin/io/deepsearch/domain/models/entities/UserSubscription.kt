package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.models.valueobjects.UserSubscriptionId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class UserSubscription(
    var id: UserSubscriptionId? = null,
    val userId: UserId,
    val planName: String,
    val tier: PlanTier,
    val maxSearches: Int,
    val priceUsd: Double,
    var usedSearches: Int = 0,
    val startDate: Instant = Clock.System.now(),
    val expiryDate: Instant?,
    val createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now(),
    var version: Long = 0
) {
    fun hasRemainingSearches(): Boolean {
        return usedSearches < maxSearches
    }

    fun consumeSearch() {
        usedSearches++
        updatedAt = Clock.System.now()
    }

    fun isExpired(): Boolean {
        return expiryDate?.let { Clock.System.now() > it } ?: false
    }

    companion object {
        fun fromPlan(userId: UserId, plan: SubscriptionPlan, startDate: Instant, expiryDate: Instant?): UserSubscription {
            return UserSubscription(
                userId = userId,
                planName = plan.planName,
                tier = plan.tier,
                maxSearches = plan.maxSearches,
                priceUsd = plan.priceUsd,
                startDate = startDate,
                expiryDate = expiryDate
            )
        }
    }
}


