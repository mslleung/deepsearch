package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.Email
import io.deepsearch.domain.models.valueobjects.OAuthProvider
import io.deepsearch.domain.models.valueobjects.PasswordHash
import io.deepsearch.domain.models.valueobjects.UserId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class User(
    var id: UserId? = null,
    val email: Email,
    var passwordHash: PasswordHash? = null,
    val oauthProvider: OAuthProvider? = null,
    val oauthProviderId: String? = null,
    val createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now(),
    var version: Long = 1
) {
    fun canAuthenticate(): Boolean {
        return passwordHash != null || (oauthProvider != null && oauthProviderId != null)
    }

    fun isOAuthUser(): Boolean {
        return oauthProvider != null && oauthProviderId != null
    }

    fun updatePassword(newPasswordHash: PasswordHash, updatedAt: Instant) {
        this.passwordHash = newPasswordHash
        this.updatedAt = updatedAt
    }
} 