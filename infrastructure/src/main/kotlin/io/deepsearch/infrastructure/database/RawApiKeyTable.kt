package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

/**
 * Table for storing encrypted raw API keys.
 * 
 * Encryption/decryption is handled at the table level using transform.
 */
class RawApiKeyTable(
    private val databaseCryptoService: IDatabaseCryptoService,
    private val userTable: UserTable
) : Table("raw_api_keys") {
    val userId = integer("user_id").references(userTable.id).uniqueIndex()
    
    // Store encrypted raw API key (encryption handled by transform)
    val encryptedRawKey = varchar("encrypted_raw_key", length = 512)
        .transform(
            wrap = { plaintext -> databaseCryptoService.decrypt(plaintext) },
            unwrap = { ciphertext -> databaseCryptoService.encrypt(ciphertext) }
        )

    override val primaryKey = PrimaryKey(userId)
}
