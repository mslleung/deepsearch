package io.deepsearch.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class SearchRequest(
    val query: String,
    val url: String,
)

@Serializable
data class SearchResponse(
    val response: String,
)