package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.entities.PeriodicIndexJob
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@Serializable
data class PeriodicIndexJobStartRequest(
    val baseUrl: String,
    val maxUrlCount: Int = 100,
    val sitemapUrl: String? = null
)

@Serializable
data class PeriodicIndexJobResponse(
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
fun PeriodicIndexJob.toResponse(): PeriodicIndexJobResponse = PeriodicIndexJobResponse(
    id = id,
    baseUrl = baseUrl,
    maxUrlCount = maxUrlCount,
    sitemapUrl = sitemapUrl,
    processedCount = processedCount,
    state = state.name,
    createdAtMs = createdAt.toEpochMilliseconds(),
    updatedAtMs = updatedAt.toEpochMilliseconds()
)

