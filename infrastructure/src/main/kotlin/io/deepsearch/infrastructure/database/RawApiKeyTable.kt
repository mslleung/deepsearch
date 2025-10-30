package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.config.DatabaseCrypto
import org.jetbrains.exposed.v1.core.Table

object RawApiKeyTable : Table("raw_api_keys") {
    // Get encryption password from environment
    private val encryptionPassword = System.getenv("API_KEY_ENCRYPTION_SECRET") 
        ?: "dev-encryption-secret-please-change-in-production"
    
    val userId = integer("user_id").references(UserTable.id).uniqueIndex()
    
    // Store encrypted raw API key using transform
    val encryptedRawKey = varchar("encrypted_raw_key", length = 512)
        .transform(
            wrap = { plaintext -> DatabaseCrypto.encrypt(plaintext, encryptionPassword) },
            unwrap = { ciphertext -> DatabaseCrypto.decrypt(ciphertext, encryptionPassword) }
        )
    
    override val primaryKey = PrimaryKey(userId)
}
