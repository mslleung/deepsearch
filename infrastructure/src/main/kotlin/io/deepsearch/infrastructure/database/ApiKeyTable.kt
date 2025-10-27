package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object ApiKeyTable : Table("api_keys") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(UserTable.id)
    val keyHash = varchar("key_hash", length = 255).uniqueIndex()
    val keyPrefix = varchar("key_prefix", length = 20)
    val name = varchar("name", length = 100)
    val createdAtEpochMs = long("created_at_epoch_ms")
    val lastUsedAtEpochMs = long("last_used_at_epoch_ms").nullable()
    val usageCount = long("usage_count").default(0)

    override val primaryKey = PrimaryKey(id)
}

