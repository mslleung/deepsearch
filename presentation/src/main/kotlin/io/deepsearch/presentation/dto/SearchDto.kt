package io.deepsearch.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class SearchRequest(
    val query: String,
    val url: String,
    val cacheExpiryMs: Long? = null, // Default 7 days = 604800000ms
)