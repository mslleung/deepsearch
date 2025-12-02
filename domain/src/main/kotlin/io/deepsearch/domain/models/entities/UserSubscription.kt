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
    val rolloverSearches: Int = 0,
    val startDate: Instant = Clock.System.now(),
    val expiryDate: Instant?,
    val stripeSubscriptionId: String? = null,
    val stripePriceId: String? = null,
    val stripePriceVersion: Int? = null,
    var stripeStatus: StripeSubscriptionStatus? = null,
    val createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now(),
    var version: Long = 0
) {
    /**
     * Total available searches including rollover credits from previous subscriptions.
     */
    val totalAvailableSearches: Int
        get() = maxSearches + rolloverSearches

    fun hasRemainingSearches(): Boolean {
        return usedSearches < totalAvailableSearches
    }

    fun consumeSearch() {
        usedSearches++
        updatedAt = Clock.System.now()
    }

    fun isExpired(): Boolean {
        return expiryDate?.let { Clock.System.now() > it } ?: false
    }

    /**
     * Calculates the remaining searches that can be rolled over to a new subscription.
     */
    fun getRemainingSearches(): Int {
        return maxOf(0, totalAvailableSearches - usedSearches)
    }

    companion object {
        fun fromPlan(
            userId: UserId,
            plan: SubscriptionPlan,
            startDate: Instant,
            expiryDate: Instant?,
            stripeSubscriptionId: String? = null,
            stripePriceId: String? = null,
            stripeStatus: StripeSubscriptionStatus? = null,
            rolloverSearches: Int = 0
        ): UserSubscription {
            return UserSubscription(
                userId = userId,
                planName = plan.planName,
                tier = plan.tier,
                maxSearches = plan.maxSearches,
                priceUsd = plan.priceUsd,
                rolloverSearches = rolloverSearches,
                startDate = startDate,
                expiryDate = expiryDate,
                stripeSubscriptionId = stripeSubscriptionId,
                stripePriceId = stripePriceId,
                stripePriceVersion = plan.priceVersion,
                stripeStatus = stripeStatus
            )
        }
    }
}


