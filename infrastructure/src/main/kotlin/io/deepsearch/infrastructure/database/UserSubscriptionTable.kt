package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

class UserSubscriptionTable(
    private val databaseCryptoService: IDatabaseCryptoService,
    private val userTable: UserTable
) : Table("user_subscriptions") {
    val id = long("id").autoIncrement()
    val userId = integer("user_id").references(userTable.id)
    val planName = varchar("plan_name", length = 50)
    val tier = varchar("tier", length = 10) // FREE or PAID
    val maxSearches = integer("max_searches")
    val priceUsd = double("price_usd")
    val usedSearches = integer("used_searches").default(0)
    val startDateEpochMs = long("start_date_epoch_ms")
    val expiryDateEpochMs = long("expiry_date_epoch_ms").nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    override val primaryKey = PrimaryKey(id)
}


