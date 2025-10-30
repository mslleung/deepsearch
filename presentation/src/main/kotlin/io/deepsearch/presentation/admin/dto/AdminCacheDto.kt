package io.deepsearch.presentation.admin.dto

import kotlinx.serialization.Serializable

@Serializable
data class AdminCacheStatsDto(
    val totalCachedPages: Int,
    val totalCachedImages: Int,
    val totalCachedPdfs: Int,
    val totalCachedTables: Int,
    val recentlyCachedUrls: List<AdminCachedUrlDto>
)

@Serializable
data class AdminCachedUrlDto(
    val url: String,
    val cachedAt: Long, // epoch millis
    val contentType: String
)

@Serializable
data class ClearCacheRequest(
    val url: String?
)

