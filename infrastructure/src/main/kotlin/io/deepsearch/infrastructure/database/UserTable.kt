package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object UserTable : Table("users") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", length = 255).uniqueIndex()
    val passwordHash = varchar("password_hash", length = 255).nullable()
    val oauthProvider = varchar("oauth_provider", length = 50).nullable()
    val oauthProviderId = varchar("oauth_provider_id", length = 255).nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    override val primaryKey = PrimaryKey(id)
} 