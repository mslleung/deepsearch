package io.deepsearch.domain.config

/**
 * Configuration for Gemini API integration.
 *
 * @property apiKey The API key for authenticating with Gemini API
 */
data class GeminiApiConfig(
    val apiKey: String
) {
    init {
        require(apiKey.isNotBlank()) { "Gemini API key cannot be blank" }
    }
}
