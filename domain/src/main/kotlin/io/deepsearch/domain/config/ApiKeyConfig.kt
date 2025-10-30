package io.deepsearch.domain.config

data class ApiKeyConfig(
    val hmacSecret: String
) {
    init {
        require(hmacSecret.isNotBlank()) { "HMAC secret cannot be blank" }
    }
}
