package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.ApiKey
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IApiKeyRepository
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface IApiKeyService {
    suspend fun generateApiKey(userId: UserId, name: String): Pair<ApiKey, String>
    suspend fun validateApiKey(rawKey: String): ApiKey?
    suspend fun listUserApiKeys(userId: UserId): List<ApiKey>
    suspend fun deleteApiKey(userId: UserId, keyId: ApiKeyId): Boolean
}

@OptIn(ExperimentalTime::class)
class ApiKeyService(
    private val apiKeyRepository: IApiKeyRepository
) : IApiKeyService {

    companion object {
        private const val API_KEY_PREFIX = "ds_live_"
        private const val KEY_LENGTH = 32
        private val SECURE_RANDOM = SecureRandom()
        private val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }

    override suspend fun generateApiKey(userId: UserId, name: String): Pair<ApiKey, String> {
        val randomPart = generateRandomString(KEY_LENGTH)
        val rawKey = "$API_KEY_PREFIX$randomPart"
        val keyHash = BCrypt.hashpw(rawKey, BCrypt.gensalt(12))
        val keyPrefix = rawKey.take(16) // First 16 chars for display

        val now = Clock.System.now()
        val apiKey = ApiKey(
            userId = userId,
            keyHash = keyHash,
            keyPrefix = keyPrefix,
            name = name,
            createdAt = now,
            lastUsedAt = null,
            usageCount = 0
        )

        val savedApiKey = apiKeyRepository.save(apiKey)
        return Pair(savedApiKey, rawKey)
    }

    override suspend fun validateApiKey(rawKey: String): ApiKey? {
        if (!rawKey.startsWith(API_KEY_PREFIX)) {
            return null
        }

        // For performance, we could add a cache here in the future
        // For now, we need to check all API keys (or add prefix to DB for faster lookup)
        // Since we're hashing, we can't do a direct DB lookup
        // This is a trade-off between security and performance
        
        // Simple approach: Hash and check
        // Better approach: Store prefix in DB and only check those matches
        // For now, let's use a findByKeyHash approach that assumes we can query by hash
        
        val keyHash = hashApiKey(rawKey)
        val apiKey = apiKeyRepository.findByKeyHash(keyHash)
        
        if (apiKey != null) {
            // Update last used time and usage count
            val now = Clock.System.now()
            apiKey.incrementUsage(now)
            apiKeyRepository.update(apiKey)
            return apiKey
        }
        
        return null
    }

    override suspend fun listUserApiKeys(userId: UserId): List<ApiKey> {
        return apiKeyRepository.findByUserId(userId)
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

    private fun hashApiKey(rawKey: String): String {
        return BCrypt.hashpw(rawKey, BCrypt.gensalt(12))
    }
}

