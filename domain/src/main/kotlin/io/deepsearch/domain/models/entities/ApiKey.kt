package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.ApiKeyType
import io.deepsearch.domain.models.valueobjects.UserId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ApiKey(
    var id: ApiKeyId? = null,
    val userId: UserId,
    val keyHash: String,
    val keyPrefix: String,
    val name: String,
    val type: ApiKeyType = ApiKeyType.REGULAR,
    val rateLimitPerMinute: Int = type.rateLimitPerMinute,
    val createdAt: Instant = Clock.System.now(),
    var lastUsedAt: Instant? = null,
    var usageCount: Long = 0
) {
    init {
        require(keyHash.isNotBlank()) { "Key hash cannot be blank" }
        require(keyPrefix.isNotBlank()) { "Key prefix cannot be blank" }
        require(name.isNotBlank()) { "Key name cannot be blank" }
        require(usageCount >= 0) { "Usage count cannot be negative" }
    }

    fun incrementUsage() {
        usageCount += 1
        lastUsedAt = Clock.System.now()
    }

    fun markAsUsed() {
        lastUsedAt = Clock.System.now()
    }

    fun isExpired(): Boolean {
        // For now, API keys don't expire. Can add expiration logic later
        return false
    }
}

