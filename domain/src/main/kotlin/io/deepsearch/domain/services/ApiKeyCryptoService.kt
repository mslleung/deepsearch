package io.deepsearch.domain.services

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface IApiKeyCryptoService {
    fun hmacSha256(rawKey: String, secret: String): String
}

/**
 * Cryptography utilities for API key hashing.
 *
 * - HMAC-SHA256: Used for all API keys (one-way, fast validation, secure)
 */
class ApiKeyCryptoService : IApiKeyCryptoService {

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }

    /**
     * Computes HMAC-SHA256 hash of the raw API key.
     * This is deterministic and allows O(1) database lookup.
     *
     * @param rawKey The raw API key to hash
     * @param secret The secret key for HMAC
     * @return Hex-encoded HMAC hash
     */
    override fun hmacSha256(rawKey: String, secret: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val secretKeySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)
        mac.init(secretKeySpec)
        val hashBytes = mac.doFinal(rawKey.toByteArray(Charsets.UTF_8))
        return hashBytes.toHexString()
    }

    /**
     * Converts byte array to hex string.
     */
    private fun ByteArray.toHexString(): String {
        return joinToString("") { byte -> "%02x".format(byte) }
    }
}