package io.deepsearch.domain.entities

import io.deepsearch.domain.models.valueobjects.Email
import io.deepsearch.domain.models.valueobjects.OAuthProvider
import io.deepsearch.domain.models.valueobjects.PasswordHash
import io.deepsearch.domain.models.valueobjects.UserId
import kotlinx.datetime.Instant

data class User(
    val id: UserId? = null,
    val email: Email,
    val passwordHash: PasswordHash? = null,
    val oauthProvider: OAuthProvider? = null,
    val oauthProviderId: String? = null,
    val displayName: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun canAuthenticate(): Boolean {
        return passwordHash != null || (oauthProvider != null && oauthProviderId != null)
    }

    fun isOAuthUser(): Boolean {
        return oauthProvider != null && oauthProviderId != null
    }

    fun updatePassword(newPasswordHash: PasswordHash): User {
        return copy(passwordHash = newPasswordHash)
    }

    fun withId(id: UserId): User = copy(id = id)
} 