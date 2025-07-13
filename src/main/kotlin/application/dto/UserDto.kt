package io.deepsearch.application.dto

import kotlinx.serialization.Serializable
import io.deepsearch.domain.entities.User
import io.deepsearch.domain.valueobjects.UserId
import io.deepsearch.domain.valueobjects.UserName
import io.deepsearch.domain.valueobjects.UserAge

@Serializable
data class CreateUserRequest(
    val name: String,
    val age: Int
)

@Serializable
data class UpdateUserRequest(
    val name: String,
    val age: Int
)

@Serializable
data class UserResponse(
    val id: Int,
    val name: String,
    val age: Int
)

fun CreateUserRequest.toDomain(): User {
    return User(
        name = UserName(name),
        age = UserAge(age)
    )
}

fun UpdateUserRequest.toDomain(id: UserId): User {
    return User(
        id = id,
        name = UserName(name),
        age = UserAge(age)
    )
}

fun User.toResponse(): UserResponse {
    return UserResponse(
        id = id!!.value,
        name = name.value,
        age = age.value
    )
} 