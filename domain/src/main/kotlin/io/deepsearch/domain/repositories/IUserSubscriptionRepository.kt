package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.UserSubscription
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.models.valueobjects.UserSubscriptionId

interface IUserSubscriptionRepository {
    suspend fun findByUserId(userId: UserId): List<UserSubscription>
    suspend fun findById(id: UserSubscriptionId): UserSubscription?
    suspend fun findByStripeSubscriptionId(stripeSubscriptionId: String): UserSubscription?
    suspend fun findAll(): List<UserSubscription>
    suspend fun save(subscription: UserSubscription): UserSubscription
    suspend fun update(subscription: UserSubscription): UserSubscription
    suspend fun delete(id: UserSubscriptionId): Boolean
}
