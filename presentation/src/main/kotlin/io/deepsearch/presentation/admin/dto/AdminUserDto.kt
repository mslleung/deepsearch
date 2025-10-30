package io.deepsearch.presentation.admin.dto

import io.deepsearch.domain.models.entities.User
import io.deepsearch.domain.models.entities.UserSubscription
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Serializable
data class AdminUserDto(
    val id: Int,
    val email: String,
    val oauthProvider: String?,
    val createdAt: Long, // epoch millis
    val updatedAt: Long,
    val subscriptions: List<AdminUserSubscriptionDto>?,
    val totalUsage: Int?
)

@OptIn(ExperimentalTime::class)
fun User.toAdminDto(
    subscriptions: List<UserSubscription>? = null,
    totalUsage: Int? = null
): AdminUserDto {
    return AdminUserDto(
        id = this.id!!.value,
        email = this.email.value,
        oauthProvider = this.oauthProvider?.name,
        createdAt = this.createdAt.toEpochMilliseconds(),
        updatedAt = this.updatedAt.toEpochMilliseconds(),
        subscriptions = subscriptions?.map { it.toAdminDto() },
        totalUsage = totalUsage
    )
}

@Serializable
data class UpdateUserRequest(
    val email: String?,
    val password: String?
)

