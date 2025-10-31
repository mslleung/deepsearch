package io.deepsearch.domain.config

/**
 * Configuration for database encryption.
 * 
 * @property encryptionSecret The secret key used for encrypting sensitive data in the database.
 *                           Should be a 256-bit (32-byte) key encoded in base64.
 */
data class DatabaseEncryptionConfig(
    val encryptionSecret: String
) {
    init {
        require(encryptionSecret.isNotBlank()) { "encryption secret cannot be blank" }
    }
}
