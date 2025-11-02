package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.entities.PrecacheJob
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@Serializable
data class PrecacheStartRequest(
    val baseUrl: String,
    val maxUrlCount: Int = 100,
    val sitemapUrl: String? = null
)

@Serializable
data class PrecacheJobResponse(
    val id: Long?,
    val baseUrl: String,
    val maxUrlCount: Int,
    val sitemapUrl: String?,
    val processedCount: Int,
    val state: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@OptIn(ExperimentalTime::class)
fun PrecacheJob.toResponse(): PrecacheJobResponse = PrecacheJobResponse(
    id = id,
    baseUrl = baseUrl,
    maxUrlCount = maxUrlCount,
    sitemapUrl = sitemapUrl,
    processedCount = processedCount,
    state = state.name,
    createdAtMs = createdAt.toEpochMilliseconds(),
    updatedAtMs = updatedAt.toEpochMilliseconds()
)


