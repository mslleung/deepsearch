package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.PlanTier
import io.deepsearch.domain.models.entities.UserSubscription
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.models.valueobjects.UserSubscriptionId
import io.deepsearch.domain.repositories.IUserSubscriptionRepository
import io.deepsearch.infrastructure.database.UserSubscriptionTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedUserSubscriptionRepository : IUserSubscriptionRepository {

    override suspend fun findByUserId(userId: UserId): List<UserSubscription> = suspendTransaction {
        UserSubscriptionTable.selectAll()
            .where { UserSubscriptionTable.userId eq userId.value }
            .map { row -> mapRowToUserSubscription(row) }
            .toList()
    }

    override suspend fun findById(id: UserSubscriptionId): UserSubscription? = suspendTransaction {
        UserSubscriptionTable.selectAll()
            .where { UserSubscriptionTable.id eq id.value }
            .map { row -> mapRowToUserSubscription(row) }
            .toList()
            .firstOrNull()
    }

    override suspend fun save(subscription: UserSubscription): UserSubscription = suspendTransaction {
        val id = UserSubscriptionTable.insert {
            it[userId] = subscription.userId.value
            it[planName] = subscription.planName
            it[tier] = subscription.tier.name
            it[maxSearches] = subscription.maxSearches
            it[priceUsd] = subscription.priceUsd
            it[usedSearches] = subscription.usedSearches
            it[startDateEpochMs] = subscription.startDate.toEpochMilliseconds()
            it[expiryDateEpochMs] = subscription.expiryDate?.toEpochMilliseconds()
            it[createdAtEpochMs] = subscription.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = subscription.updatedAt.toEpochMilliseconds()
        }[UserSubscriptionTable.id]

        subscription.id = UserSubscriptionId(id)
        subscription
    }

    override suspend fun update(subscription: UserSubscription): UserSubscription = suspendTransaction {
        UserSubscriptionTable.update({ UserSubscriptionTable.id eq subscription.id!!.value }) {
            it[usedSearches] = subscription.usedSearches
            it[updatedAtEpochMs] = subscription.updatedAt.toEpochMilliseconds()
        }
        subscription
    }

    private fun mapRowToUserSubscription(row: ResultRow): UserSubscription {
        return UserSubscription(
            id = UserSubscriptionId(row[UserSubscriptionTable.id]),
            userId = UserId(row[UserSubscriptionTable.userId]),
            planName = row[UserSubscriptionTable.planName],
            tier = PlanTier.valueOf(row[UserSubscriptionTable.tier]),
            maxSearches = row[UserSubscriptionTable.maxSearches],
            priceUsd = row[UserSubscriptionTable.priceUsd],
            usedSearches = row[UserSubscriptionTable.usedSearches],
            startDate = Instant.fromEpochMilliseconds(row[UserSubscriptionTable.startDateEpochMs]),
            expiryDate = row[UserSubscriptionTable.expiryDateEpochMs]?.let { Instant.fromEpochMilliseconds(it) },
            createdAt = Instant.fromEpochMilliseconds(row[UserSubscriptionTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[UserSubscriptionTable.updatedAtEpochMs])
        )
    }
}
