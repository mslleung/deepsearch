package io.deepsearch.domain.entities

import io.deepsearch.domain.valueobjects.WebUrl
import io.deepsearch.domain.valueobjects.SearchQuery

data class WebScrapeQuery(
    val url: WebUrl,
    val query: SearchQuery
) {
    fun isValidForScraping(): Boolean = url.value.isNotBlank() && query.value.isNotBlank()
    
    fun getUrlDomain(): String {
        return try {
            val urlParts = url.value.split("://")[1].split("/")[0]
            urlParts.removePrefix("www.")
        } catch (e: Exception) {
            "unknown"
        }
    }
} 