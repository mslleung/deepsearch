package io.deepsearch.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class SearchRequest(
    val query: String,
    val url: String,
    val maxCacheAge: Long? = null,
)