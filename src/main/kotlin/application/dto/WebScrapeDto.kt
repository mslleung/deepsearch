package io.deepsearch.application.dto

import kotlinx.serialization.Serializable
import io.deepsearch.domain.entities.WebScrapeQuery
import io.deepsearch.domain.entities.WebScrapeResult
import io.deepsearch.domain.valueobjects.WebUrl
import io.deepsearch.domain.valueobjects.SearchQuery

@Serializable
data class WebScrapeRequest(
    val url: String,
    val query: String
)

@Serializable
data class WebScrapeResponse(
    val url: String,
    val query: String,
    val response: String,
    val timestamp: Long = System.currentTimeMillis()
)

fun WebScrapeRequest.toDomain(): WebScrapeQuery {
    return WebScrapeQuery(
        url = WebUrl(url),
        query = SearchQuery(query)
    )
}

fun WebScrapeResult.toResponse(): WebScrapeResponse {
    return WebScrapeResponse(
        url = url.value,
        query = query.value,
        response = response,
        timestamp = timestamp
    )
} 