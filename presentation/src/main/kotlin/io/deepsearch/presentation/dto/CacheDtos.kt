package io.deepsearch.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class CacheItem(
    val url: String,
    val updatedAtEpochMs: Long?,
    val httpStatus: Int?,
    val mimeType: String?
)

@Serializable
data class CacheContent(
    val markdown: String?
)


