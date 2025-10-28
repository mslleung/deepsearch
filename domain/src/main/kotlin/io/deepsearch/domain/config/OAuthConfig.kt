package io.deepsearch.domain.config

data class OAuthConfig(
    val google: GoogleOAuthConfig
)

data class GoogleOAuthConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUrl: String
)

