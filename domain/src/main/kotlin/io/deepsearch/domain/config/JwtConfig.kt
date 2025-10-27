package io.deepsearch.domain.config

data class JwtConfig(
    val publicKey: String,
    val privateKey: String,
    val issuer: String,
    val audience: String,
    val realm: String
) {
    companion object {
        val CLAIM_USER_ID = "user-id"
    }
}