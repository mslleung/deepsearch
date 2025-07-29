package io.deepsearch.domain.valueobjects

import java.net.URI

class SearchQuery(public val query: String, public val url: String) {

    init {
        require(query.isNotBlank()) { "Search query cannot be blank" }
        require(query.length <= 1000) { "Search query cannot exceed 1000 characters" }

        require(url.isNotBlank()) { "URL cannot be blank" }

        val urlObj = URI.create(url).toURL()
        require(urlObj.protocol in listOf("http", "https")) {
            "URL must use HTTP or HTTPS protocol"
        }
    }
} 