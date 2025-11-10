package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.PlanTier
import io.deepsearch.domain.models.entities.UserSubscription
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.models.valueobjects.UserSubscriptionId
import io.deepsearch.domain.repositories.IUserSubscriptionRepository
import io.deepsearch.infrastructure.database.UserSubscriptionTable
import io.deepsearch.infrastructure.services.TransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedUserSubscriptionRepository(
    private val userSubscriptionTable: UserSubscriptionTable,
    private val transactionService: TransactionService
) : IUserSubscriptionRepository {

    override suspend fun findByUserId(userId: UserId): List<UserSubscription> = transactionService.withTransaction {
        userSubscriptionTable.selectAll()
            .where { userSubscriptionTable.userId eq userId.value }
            .map { row -> mapRowToUserSubscription(row) }
            .toList()
    }

    override suspend fun findById(id: UserSubscriptionId): UserSubscription? = transactionService.withTransaction {
        userSubscriptionTable.selectAll()
            .where { userSubscriptionTable.id eq id.value }
            .map { row -> mapRowToUserSubscription(row) }
            .toList()
            .firstOrNull()
    }

    override suspend fun findAll(): List<UserSubscription> = transactionService.withTransaction {
        userSubscriptionTable.selectAll()
            .map { row -> mapRowToUserSubscription(row) }
            .toList()
    }

    override suspend fun save(subscription: UserSubscription): UserSubscription = transactionService.withTransaction {
        val id = userSubscriptionTable.insert {
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
            it[version] = subscription.version
        }[userSubscriptionTable.id]

        subscription.id = UserSubscriptionId(id)
        subscription
    }

    override suspend fun update(subscription: UserSubscription): UserSubscription = transactionService.withTransaction {
        val affectedRows = userSubscriptionTable.update({ 
            (userSubscriptionTable.id eq subscription.id!!.value) and (userSubscriptionTable.version eq subscription.version) 
        }) {
            it[usedSearches] = subscription.usedSearches
            it[updatedAtEpochMs] = subscription.updatedAt.toEpochMilliseconds()
            it[version] = subscription.version + 1
        }
        
        if (affectedRows == 0) {
            throw OptimisticLockException("UserSubscription", subscription.id!!.value, subscription.version)
        }
        
        subscription.version += 1
        subscription
    }

    override suspend fun delete(id: UserSubscriptionId): Boolean = transactionService.withTransaction {
        userSubscriptionTable.deleteWhere { userSubscriptionTable.id eq id.value } > 0
    }

    private fun mapRowToUserSubscription(row: ResultRow): UserSubscription {
        return UserSubscription(
            id = UserSubscriptionId(row[userSubscriptionTable.id]),
            userId = UserId(row[userSubscriptionTable.userId]),
            planName = row[userSubscriptionTable.planName],
            tier = PlanTier.valueOf(row[userSubscriptionTable.tier]),
            maxSearches = row[userSubscriptionTable.maxSearches],
            priceUsd = row[userSubscriptionTable.priceUsd],
            usedSearches = row[userSubscriptionTable.usedSearches],
            startDate = Instant.fromEpochMilliseconds(row[userSubscriptionTable.startDateEpochMs]),
            expiryDate = row[userSubscriptionTable.expiryDateEpochMs]?.let { Instant.fromEpochMilliseconds(it) },
            createdAt = Instant.fromEpochMilliseconds(row[userSubscriptionTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[userSubscriptionTable.updatedAtEpochMs]),
            version = row[userSubscriptionTable.version]
        )
    }
}
