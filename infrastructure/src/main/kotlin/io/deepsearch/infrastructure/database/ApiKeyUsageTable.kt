package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.config.DatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

class ApiKeyUsageTable(
    private val databaseCryptoService: DatabaseCryptoService,
    private val apiKeyTable: ApiKeyTable
) : Table("api_key_requests") {
    val id = long("id").autoIncrement()
    val apiKeyId = integer("api_key_id").references(apiKeyTable.id)
    val requestedAtEpochMs = long("requested_at_epoch_ms")
    val version = long("version").default(0)

    override val primaryKey = PrimaryKey(id)
}

