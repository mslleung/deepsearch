package io.deepsearch.domain.config

/**
 * Configuration for the deepsearch-browser service.
 *
 * @property url The URL of the browser service (e.g., "http://localhost:8090")
 */
data class DeepSearchBrowserConfig(
    val url: String
) {
    init {
        require(url.isNotBlank()) { "DeepSearch browser URL cannot be blank" }
    }
}
