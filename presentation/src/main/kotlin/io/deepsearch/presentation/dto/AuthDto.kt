package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.entities.User
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val token: String,
    val user: UserResponse,
    val playgroundKey: String
)

@Serializable
data class UserResponse(
    val id: Int,
    val email: String,
    val oauthProvider: String?,
    val createdAt: Long
)

@OptIn(ExperimentalTime::class)
fun User.toUserResponse(): UserResponse {
    return UserResponse(
        id = id!!.value,
        email = email.value,
        oauthProvider = oauthProvider?.name,
        createdAt = createdAt.toEpochMilliseconds()
    )
}
