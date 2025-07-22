package io.deepsearch.domain.valueobjects

import io.ktor.http.Url

class SearchQuery(public val query: String, url: String) {

    public val url = Url(url)

    init {
        require(query.isNotBlank()) { "Search query cannot be blank" }
        require(query.length <= 1000) { "Search query cannot exceed 1000 characters" }
    }
} 