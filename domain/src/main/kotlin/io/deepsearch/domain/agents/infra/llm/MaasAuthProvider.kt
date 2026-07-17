package io.deepsearch.domain.agents.infra.llm

import com.google.auth.oauth2.GoogleCredentials

/**
 * Provides refreshable GCP OAuth2 access tokens for the Vertex AI MaaS OpenAI-compatible endpoint.
 *
 * Tokens expire approximately every hour. Call [getAccessToken] before each API request
 * to ensure a valid token is used.
 */
class MaasAuthProvider(private val credentials: GoogleCredentials) {

    companion object {
        private val CLOUD_PLATFORM_SCOPE = listOf("https://www.googleapis.com/auth/cloud-platform")

        fun fromApplicationDefault(): MaasAuthProvider {
            val credentials = GoogleCredentials.getApplicationDefault()
                .createScoped(CLOUD_PLATFORM_SCOPE)
            return MaasAuthProvider(credentials)
        }
    }

    fun getAccessToken(): String {
        credentials.refreshIfExpired()
        return credentials.accessToken.tokenValue
    }
}
