package io.deepsearch.application.services

import io.deepsearch.domain.config.ApiKeyConfig
import io.deepsearch.domain.models.entities.ApiKey
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.ApiKeyType
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IApiKeyRepository
import io.deepsearch.domain.services.IApiKeyCryptoService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface IApiKeyService {
    suspend fun generateApiKey(userId: UserId, name: String, type: ApiKeyType = ApiKeyType.REGULAR): Pair<ApiKey, String>
    suspend fun validateApiKey(rawKey: String): Boolean
    suspend fun incrementApiKeyUsage(rawKey: String)
    suspend fun listUserApiKeys(userId: UserId): List<ApiKey>
    suspend fun getApiKeyById(keyId: ApiKeyId): ApiKey?
    suspend fun getApiKeyByRawKey(rawKey: String): ApiKey?
    suspend fun deleteApiKey(userId: UserId, keyId: ApiKeyId): Boolean
    suspend fun getOrCreatePlaygroundKey(userId: UserId): String
    suspend fun getRawPlaygroundKey(userId: UserId): String?
}

@OptIn(ExperimentalTime::class)
class ApiKeyService(
    private val apiKeyRepository: IApiKeyRepository,
    private val apiKeyConfig: ApiKeyConfig,
    private val apiKeyCryptoService: IApiKeyCryptoService
) : IApiKeyService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val KEY_LENGTH = 32
        private val SECURE_RANDOM = SecureRandom()
        private val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }

    override suspend fun generateApiKey(userId: UserId, name: String, type: ApiKeyType): Pair<ApiKey, String> {
        // Enforce maximum 1 regular key per user
        if (type == ApiKeyType.REGULAR) {
            val existingCount = apiKeyRepository.countByUserIdAndType(userId, ApiKeyType.REGULAR)
            if (existingCount >= 1) {
                throw IllegalStateException("User already has the maximum number of regular API keys (1). Please delete the existing key before creating a new one.")
            }
        }
        
        val randomPart = generateRandomString(KEY_LENGTH)
        val rawKey = "${type.prefix}$randomPart"
        val keyPrefix = rawKey.take(16) // First 16 chars for display
        
        // Hash all keys with HMAC (one-way, secure, fast validation)
        val keyHash = apiKeyCryptoService.hmacSha256(rawKey, apiKeyConfig.hmacSecret)

        val now = Clock.System.now()
        val apiKey = ApiKey(
            userId = userId,
            keyHash = keyHash,
            keyPrefix = keyPrefix,
            name = name,
            type = type,
            rateLimitPerMinute = type.rateLimitPerMinute,
            createdAt = now,
            lastUsedAt = null,
            usageCount = 0
        )

        val savedApiKey = apiKeyRepository.save(apiKey)
        
        // For playground keys, also store the raw key encrypted
        if (type == ApiKeyType.PLAYGROUND) {
            apiKeyRepository.saveRawApiKey(userId, rawKey)
        }
        
        return Pair(savedApiKey, rawKey)
    }

    override suspend fun validateApiKey(rawKey: String): Boolean {
        // Check if it matches any of our key types
        val matchesPrefix = ApiKeyType.entries.any { rawKey.startsWith(it.prefix) }
        if (!matchesPrefix) {
            return false
        }

        // Compute HMAC hash of the raw key (deterministic, O(1) lookup)
        val computedHash = apiKeyCryptoService.hmacSha256(rawKey, apiKeyConfig.hmacSecret)
        
        // Direct lookup by hash with unique index
        val apiKey = apiKeyRepository.findByKeyHash(computedHash)

        return apiKey != null
    }

    override suspend fun incrementApiKeyUsage(rawKey: String) {
        val computedHash = apiKeyCryptoService.hmacSha256(rawKey, apiKeyConfig.hmacSecret)
        val apiKey = apiKeyRepository.findByKeyHash(computedHash)
            ?: throw IllegalStateException("API key not found")
        
        // Update last used time and usage count
        apiKey.incrementUsage()
        apiKeyRepository.update(apiKey)
    }

    override suspend fun getOrCreatePlaygroundKey(userId: UserId): String {
        // Try to find existing raw playground key
        val existingRawKey = apiKeyRepository.findRawApiKey(userId)
        
        if (existingRawKey != null) {
            return existingRawKey
        }
        
        // Create new playground key (first time)
        val (_, rawKey) = generateApiKey(userId, "Web App Playground", ApiKeyType.PLAYGROUND)
        return rawKey
    }
    
    override suspend fun getRawPlaygroundKey(userId: UserId): String? {
        return apiKeyRepository.findRawApiKey(userId)
    }

    override suspend fun listUserApiKeys(userId: UserId): List<ApiKey> {
        return apiKeyRepository.findByUserId(userId)
    }

    override suspend fun getApiKeyById(keyId: ApiKeyId): ApiKey? {
        return apiKeyRepository.findById(keyId)
    }

    override suspend fun getApiKeyByRawKey(rawKey: String): ApiKey? {
        val computedHash = apiKeyCryptoService.hmacSha256(rawKey, apiKeyConfig.hmacSecret)
        return apiKeyRepository.findByKeyHash(computedHash)
    }

    override suspend fun deleteApiKey(userId: UserId, keyId: ApiKeyId): Boolean {
        // Verify the key belongs to the user
        val apiKey = apiKeyRepository.findById(keyId)
        if (apiKey?.userId != userId) {
            throw IllegalAccessError("$keyId does not belong to user $userId")
        }
        return apiKeyRepository.delete(keyId)
    }

    private fun generateRandomString(length: Int): String {
        return (1..length)
            .map { CHARS[SECURE_RANDOM.nextInt(CHARS.length)] }
            .joinToString("")
    }
}

