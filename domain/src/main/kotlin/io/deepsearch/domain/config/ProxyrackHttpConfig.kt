package io.deepsearch.domain.config

/**
 * Configuration for Proxyrack residential proxy.
 * All fields are mandatory. Loaded from environment variables.
 *
 * @param endpoint The Proxyrack proxy endpoint (e.g., "premium.residential.proxyrack.net:10000")
 * @param username The Proxyrack username
 * @param apiKey The Proxyrack API key
 */
data class ProxyrackHttpConfig(
    val endpoint: String,
    val username: String,
    val apiKey: String
) {
    init {
        require(endpoint.isNotBlank()) { "Proxyrack endpoint must not be blank" }
        require(username.isNotBlank()) { "Proxyrack username must not be blank" }
        require(apiKey.isNotBlank()) { "Proxyrack API key must not be blank" }
    }

    /**
     * Build the proxy URL with authentication.
     * Format: http://username:apiKey@endpoint
     */
    fun toProxyUrl(): String {
        return "http://$username:$apiKey@$endpoint"
    }
}
