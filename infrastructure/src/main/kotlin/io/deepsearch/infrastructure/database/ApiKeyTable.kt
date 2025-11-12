package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

class ApiKeyTable(
    private val databaseCryptoService: IDatabaseCryptoService,
    private val userTable: UserTable
) : Table("api_keys") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(userTable.id)
    val keyHash = varchar("key_hash", length = 255).uniqueIndex()
    val keyPrefix = varchar("key_prefix", length = 20)
    val name = varchar("name", length = 100)
    val type = varchar("type", length = 20).default("REGULAR")
    val rateLimitPerMinute = integer("rate_limit_per_minute").default(20)
    val createdAtEpochMs = long("created_at_epoch_ms")
    val lastUsedAtEpochMs = long("last_used_at_epoch_ms").nullable()
    val usageCount = long("usage_count").default(0)
    val version = long("version").default(0)
    val deletedAtEpochMs = long("deleted_at_epoch_ms").nullable()
    
    // Store encrypted raw API key (encryption handled by transform)
    // Only populated for PLAYGROUND keys
    val encryptedRawKey = varchar("encrypted_raw_key", length = 512).nullable()
        .transform(
            wrap = { plaintext -> plaintext?.let { databaseCryptoService.decrypt(it) } },
            unwrap = { ciphertext -> ciphertext?.let { databaseCryptoService.encrypt(it) } }
        )

    override val primaryKey = PrimaryKey(id)
}

