package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.entities.PrecacheJob
import kotlinx.serialization.Serializable

@Serializable
data class PrecacheJobResponse(
    val id: Long?,
    val baseUrl: String,
    val maxUrlCount: Int,
    val processedCount: Int,
    val state: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

fun PrecacheJob.toResponse(): PrecacheJobResponse = PrecacheJobResponse(
    id = id,
    baseUrl = baseUrl,
    maxUrlCount = maxUrlCount,
    processedCount = processedCount,
    state = state.name,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs
)


