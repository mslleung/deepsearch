package io.deepsearch.presentation.admin.dto

import io.deepsearch.domain.models.entities.ApiKey
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Serializable
data class AdminApiKeyDto(
    val id: Int,
    val userId: Int,
    val keyPrefix: String, // Show prefix only for security
    val name: String,
    val type: String,
    val rateLimitPerMinute: Int,
    val createdAt: Long, // epoch millis
    val lastUsedAt: Long?, // epoch millis
    val usageCount: Long,
    val deletedAt: Long? // epoch millis
)

@OptIn(ExperimentalTime::class)
fun ApiKey.toAdminDto(): AdminApiKeyDto {
    return AdminApiKeyDto(
        id = this.id!!.value,
        userId = this.userId.value,
        keyPrefix = this.keyPrefix,
        name = this.name,
        type = this.type.name,
        rateLimitPerMinute = this.rateLimitPerMinute,
        createdAt = this.createdAt.toEpochMilliseconds(),
        lastUsedAt = this.lastUsedAt?.toEpochMilliseconds(),
        usageCount = this.usageCount,
        deletedAt = this.deletedAt?.toEpochMilliseconds()
    )
}

@Serializable
data class AdminApiKeyWithUsageDto(
    val apiKey: AdminApiKeyDto,
    val recentUsage: Map<String, Int> // date to count
)

