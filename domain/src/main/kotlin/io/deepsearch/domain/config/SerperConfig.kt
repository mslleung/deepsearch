package io.deepsearch.domain.config

/**
 * Configuration for serper.dev API integration.
 *
 * @property apiKey The API key for authenticating with serper.dev
 */
data class SerperConfig(
    val apiKey: String
) {
    init {
        require(apiKey.isNotBlank()) { "Serper API key cannot be blank" }
    }
}
