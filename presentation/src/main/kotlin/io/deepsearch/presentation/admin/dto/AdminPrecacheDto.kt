package io.deepsearch.presentation.admin.dto

import io.deepsearch.domain.models.entities.PrecacheJob
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Serializable
data class AdminPrecacheJobDto(
    val id: Long,
    val baseUrl: String,
    val maxUrlCount: Int,
    val sitemapUrl: String?,
    val processedCount: Int,
    val state: String,
    val createdAt: Long, // epoch millis
    val updatedAt: Long
)

@OptIn(ExperimentalTime::class)
fun PrecacheJob.toAdminDto(): AdminPrecacheJobDto {
    return AdminPrecacheJobDto(
        id = this.id!!,
        baseUrl = this.baseUrl,
        maxUrlCount = this.maxUrlCount,
        sitemapUrl = this.sitemapUrl,
        processedCount = this.processedCount,
        state = this.state.name,
        createdAt = this.createdAt.toEpochMilliseconds(),
        updatedAt = this.updatedAt.toEpochMilliseconds()
    )
}

