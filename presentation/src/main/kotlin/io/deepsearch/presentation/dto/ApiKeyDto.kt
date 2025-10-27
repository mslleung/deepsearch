package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.entities.ApiKey
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@Serializable
data class CreateApiKeyRequest(
    val name: String
)

@Serializable
data class ApiKeyResponse(
    val id: Int,
    val name: String,
    val keyPrefix: String,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val usageCount: Long
)

@Serializable
data class CreateApiKeyResponse(
    val apiKey: ApiKeyResponse,
    val rawKey: String // Only returned once at creation
)

@OptIn(ExperimentalTime::class)
fun ApiKey.toApiKeyResponse(): ApiKeyResponse {
    return ApiKeyResponse(
        id = id!!.value,
        name = name,
        keyPrefix = keyPrefix,
        createdAt = createdAt.toEpochMilliseconds(),
        lastUsedAt = lastUsedAt?.toEpochMilliseconds(),
        usageCount = usageCount
    )
}

