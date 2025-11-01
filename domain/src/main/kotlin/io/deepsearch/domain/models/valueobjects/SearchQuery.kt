package io.deepsearch.domain.models.valueobjects

import java.net.URI

data class SearchQuery(val query: String, val url: String, val sitemapUrl: String? = null) {

    init {
        require(query.isNotBlank()) { "Search query cannot be blank" }
        require(query.length <= 1000) { "Search query cannot exceed 1000 characters" }

        require(url.isNotBlank()) { "URL cannot be blank" }

        val urlObj = URI.create(url).toURL()
        require(urlObj.protocol in listOf("http", "https")) {
            "URL must use HTTP or HTTPS protocol"
        }

        // Validate sitemap URL if provided
        if (sitemapUrl != null) {
            require(sitemapUrl.isNotBlank()) { "Sitemap URL cannot be blank" }
            val sitemapUrlObj = URI.create(sitemapUrl).toURL()
            require(sitemapUrlObj.protocol in listOf("http", "https")) {
                "Sitemap URL must use HTTP or HTTPS protocol"
            }
        }
    }
} 