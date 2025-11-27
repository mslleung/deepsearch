package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.entities.PeriodicIndexConfig
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.UncachedUrlAccess
import io.deepsearch.domain.models.valueobjects.UrlAccess
import kotlinx.serialization.Serializable

@Serializable
data class PeriodicIndexConfigRequest(
    val url: String,
    val sitemapUrl: String? = null,
    val periodDays: Int?, // null means one-off
    val maxUrlCount: Int = PeriodicIndexConfig.DEFAULT_MAX_URL_COUNT
)

@Serializable
data class PeriodicIndexConfigResponse(
    val url: String,
    val sitemapUrl: String?,
    val periodDays: Int?,
    val maxUrlCount: Int,
    val enabled: Boolean,
    val lastRunAt: Long?,
    val nextRunAt: Long?
)

@Serializable
data class PeriodicIndexJobHistoryResponse(
    val jobs: List<PeriodicIndexJobResponse>,
    val totalCount: Int
)

@Serializable
data class PeriodicIndexJobUrlResponse(
    val id: Long,
    val url: String,
    val status: String,  // "CACHED", "UNCACHED", "FAILED"
    val pageTitle: String?,
    val errorMessage: String?,
    val processedAtMs: Long
)

@Serializable
data class PeriodicIndexJobUrlListResponse(
    val urls: List<PeriodicIndexJobUrlResponse>,
    val totalCount: Int
)

fun PeriodicIndexConfig.toResponse(): PeriodicIndexConfigResponse {
    return PeriodicIndexConfigResponse(
        url = url,
        sitemapUrl = sitemapUrl,
        periodDays = periodDays,
        maxUrlCount = maxUrlCount,
        enabled = enabled,
        lastRunAt = lastRunAt,
        nextRunAt = nextRunAt
    )
}

@OptIn(kotlin.time.ExperimentalTime::class)
fun UrlAccess.toPeriodicIndexJobUrlResponse(id: Long, pageTitle: String?): PeriodicIndexJobUrlResponse {
    return PeriodicIndexJobUrlResponse(
        id = id,
        url = url,
        status = when (this) {
            is CachedUrlAccess -> "CACHED"
            is UncachedUrlAccess -> "UNCACHED"
            is FailedUrlAccess -> "FAILED"
        },
        pageTitle = pageTitle,
        errorMessage = if (this is FailedUrlAccess) message else null,
        processedAtMs = timestamp.toEpochMilliseconds()
    )
}
