package io.deepsearch.infrastructure.services

import io.deepsearch.domain.config.DatabaseEncryptionConfig
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface IDatabaseCryptoService {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

/**
 * Service for database column encryption using AES-256-GCM.
 * 
 * Provides symmetric encryption/decryption for sensitive data stored in the database.
 * Each encryption operation generates a random IV that is prepended to the ciphertext.
 */
class DatabaseCryptoService(
    private val config: DatabaseEncryptionConfig
) : IDatabaseCryptoService {

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }
    
    /**
     * Derives a secret key from the encryption secret.
     * For production, use a properly generated 256-bit key.
     */
    private fun getSecretKey(): SecretKey {
        // Use first 32 bytes of secret (pad or truncate as needed)
        val keyBytes = config.encryptionSecret.toByteArray(Charsets.UTF_8).let { bytes ->
            when {
                bytes.size >= 32 -> bytes.sliceArray(0 until 32)
                else -> bytes + ByteArray(32 - bytes.size)
            }
        }
        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }
    
    /**
     * Encrypts plaintext using AES-256-GCM.
     * 
     * @param plaintext The text to encrypt
     * @return Base64-encoded string containing IV + ciphertext
     */
    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        
        // Combine IV + encrypted data
        val combined = iv + encrypted
        return Base64.getEncoder().encodeToString(combined)
    }
    
    /**
     * Decrypts ciphertext encrypted with AES-256-GCM.
     * 
     * @param ciphertext Base64-encoded string containing IV + ciphertext
     * @return Decrypted plaintext
     */
    override fun decrypt(ciphertext: String): String {
        val combined = Base64.getDecoder().decode(ciphertext)
        
        // Extract IV (first 12 bytes for GCM) and encrypted data
        val iv = combined.sliceArray(0 until GCM_IV_LENGTH)
        val encrypted = combined.sliceArray(GCM_IV_LENGTH until combined.size)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        
        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }
}

