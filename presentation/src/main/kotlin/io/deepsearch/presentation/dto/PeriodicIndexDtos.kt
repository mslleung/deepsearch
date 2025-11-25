package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.entities.PeriodicIndexConfig
import kotlinx.serialization.Serializable

@Serializable
data class PeriodicIndexConfigRequest(
    val url: String,
    val sitemapUrl: String? = null,
    val periodDays: Int? // null means one-off
)

@Serializable
data class PeriodicIndexConfigResponse(
    val url: String,
    val sitemapUrl: String?,
    val periodDays: Int?,
    val enabled: Boolean,
    val lastRunAt: Long?,
    val nextRunAt: Long?
)

@Serializable
data class PeriodicIndexJobHistoryResponse(
    val jobs: List<PeriodicIndexJobResponse>,
    val totalCount: Int
)

fun PeriodicIndexConfig.toResponse(): PeriodicIndexConfigResponse {
    return PeriodicIndexConfigResponse(
        url = url,
        sitemapUrl = sitemapUrl,
        periodDays = periodDays,
        enabled = enabled,
        lastRunAt = lastRunAt,
        nextRunAt = nextRunAt
    )
}
