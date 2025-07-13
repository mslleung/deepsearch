package io.deepsearch.application.services

import io.deepsearch.application.dto.*
import io.deepsearch.domain.entities.User
import io.deepsearch.domain.repositories.UserRepository
import io.deepsearch.domain.valueobjects.UserId
import io.deepsearch.domain.exceptions.UserNotFoundException

class UserService(
    private val userRepository: UserRepository
) {
    suspend fun createUser(request: CreateUserRequest): UserResponse {
        val user = request.toDomain()
        val savedUser = userRepository.save(user)
        return savedUser.toResponse()
    }

    suspend fun getUserById(id: Int): UserResponse {
        val userId = UserId(id)
        val user = userRepository.findById(userId)
            ?: throw UserNotFoundException(id)
        return user.toResponse()
    }

    suspend fun getAllUsers(): List<UserResponse> {
        return userRepository.findAll().map { it.toResponse() }
    }

    suspend fun updateUser(id: Int, request: UpdateUserRequest): UserResponse {
        val userId = UserId(id)
        
        // Check if user exists
        if (!userRepository.exists(userId)) {
            throw UserNotFoundException(id)
        }
        
        val user = request.toDomain(userId)
        val updatedUser = userRepository.update(user)
        return updatedUser.toResponse()
    }

    suspend fun deleteUser(id: Int): Boolean {
        val userId = UserId(id)
        
        // Check if user exists
        if (!userRepository.exists(userId)) {
            throw UserNotFoundException(id)
        }
        
        return userRepository.delete(userId)
    }
} 