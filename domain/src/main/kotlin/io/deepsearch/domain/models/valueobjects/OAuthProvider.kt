package io.deepsearch.domain.models.valueobjects

enum class OAuthProvider {
    GOOGLE,
    GITHUB,
    FACEBOOK;

    companion object {
        fun fromString(value: String): OAuthProvider {
            return entries.find { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown OAuth provider: $value")
        }
    }
}

