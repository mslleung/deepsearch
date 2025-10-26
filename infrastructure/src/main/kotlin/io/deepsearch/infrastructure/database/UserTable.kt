package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.timestamp

object UserTable : Table("users") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", length = 255).uniqueIndex()
    val passwordHash = varchar("password_hash", length = 255).nullable()
    val oauthProvider = varchar("oauth_provider", length = 50).nullable()
    val oauthProviderId = varchar("oauth_provider_id", length = 255).nullable()
    val displayName = varchar("display_name", length = 255).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
} 