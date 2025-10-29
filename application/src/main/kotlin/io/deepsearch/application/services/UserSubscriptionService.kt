package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.PlanTier
import io.deepsearch.domain.models.entities.SubscriptionPlan
import io.deepsearch.domain.models.entities.UserSubscription
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IUserSubscriptionRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

interface IUserSubscriptionService {
    /**
     * Returns the subscription that has remaining balance and is closest to expiry.
     */
    suspend fun getUsableUserSubscription(userId: UserId): UserSubscription?
    suspend fun getUserAllActiveSubscriptions(userId: UserId): List<UserSubscription>
    suspend fun upgradePlan(userId: UserId, plan: SubscriptionPlan): UserSubscription
    suspend fun checkUsageLimit(userId: UserId): Boolean
    suspend fun consumeUsage(userId: UserId)
}

@OptIn(ExperimentalTime::class)
class UserSubscriptionService(
    private val userSubscriptionRepository: IUserSubscriptionRepository
) : IUserSubscriptionService {

    override suspend fun getUsableUserSubscription(userId: UserId): UserSubscription? {
        val allSubscriptions = userSubscriptionRepository.findByUserId(userId)
        
        // Filter to non-expired subscriptions
        val activeSubscriptions = allSubscriptions.filter { !it.isExpired() }

        // Separate paid and free plans
        val paidPlans = activeSubscriptions.filter { it.tier == PlanTier.PAID }
        val freePlans = activeSubscriptions.filter { it.tier == PlanTier.FREE }

        // If user has paid plans, return the one closest to expiry that has remaining balance
        if (paidPlans.isNotEmpty()) {
            return paidPlans
                .filter { it.hasRemainingSearches() }
                .sortedBy { it.expiryDate }
                .firstOrNull()
        }

        // If no paid plans, return the free plan if it has remaining balance
        return freePlans.firstOrNull { it.hasRemainingSearches() }
    }

    override suspend fun getUserAllActiveSubscriptions(userId: UserId): List<UserSubscription> {
        val allSubscriptions = userSubscriptionRepository.findByUserId(userId)
        return allSubscriptions
            .filter { !it.isExpired() }
            .sortedBy { it.expiryDate }
    }

    override suspend fun upgradePlan(userId: UserId, plan: SubscriptionPlan): UserSubscription {
        val now = Clock.System.now()

        // Create new subscription
        val expiryDate = if (plan.tier == PlanTier.FREE) {
            null // Free plans don't expire
        } else {
            now + 30.days // Paid plans expire in 30 days
        }

        val newSubscription = UserSubscription.fromPlan(userId, plan, now, expiryDate)
        return userSubscriptionRepository.save(newSubscription)
    }

    override suspend fun checkUsageLimit(userId: UserId): Boolean {
        val usableSubscription = getUsableUserSubscription(userId)

        return usableSubscription != null
    }

    override suspend fun consumeUsage(userId: UserId) {
        val allSubscriptions = userSubscriptionRepository.findByUserId(userId)
        val validSubscriptions = allSubscriptions
            .filter { !it.isExpired() }
            .sortedBy { it.expiryDate } // Use oldest (closest to expiry) first

        if (validSubscriptions.isEmpty()) {
            throw IllegalStateException("No valid subscriptions found for user $userId")
        }

        // Find first subscription with remaining searches
        for (subscription in validSubscriptions) {
            if (subscription.hasRemainingSearches()) {
                subscription.consumeSearch()
                userSubscriptionRepository.update(subscription)
                return
            }
        }

        throw IllegalStateException("No subscriptions with remaining searches for user $userId")
    }
}
