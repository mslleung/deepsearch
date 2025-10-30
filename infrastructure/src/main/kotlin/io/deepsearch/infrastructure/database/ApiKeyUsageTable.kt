package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object ApiKeyUsageTable : Table("api_key_requests") {
    val id = long("id").autoIncrement()
    val apiKeyId = integer("api_key_id").references(ApiKeyTable.id)
    val requestedAtEpochMs = long("requested_at_epoch_ms")
    val version = long("version").default(1)

    override val primaryKey = PrimaryKey(id)
}

