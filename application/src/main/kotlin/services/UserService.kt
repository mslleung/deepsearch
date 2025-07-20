package io.deepsearch.application.services

import io.deepsearch.domain.entities.User
import io.deepsearch.domain.repositories.UserRepository
import io.deepsearch.domain.valueobjects.UserId
import io.deepsearch.domain.exceptions.UserNotFoundException

class UserService(
    private val userRepository: UserRepository
) {
    suspend fun createUser(user: User): UserId {
        val savedUser = userRepository.save(user)
        return savedUser.id!!
    }

    suspend fun getUserById(userId: UserId): User {
        val user = userRepository.findById(userId)
            ?: throw UserNotFoundException(userId)
        return user
    }

    suspend fun getAllUsers(): List<User> {
        return userRepository.findAll()
    }

    suspend fun updateUser(userId: UserId, user: User): User {
        // Check if user exists
        if (!userRepository.exists(userId)) {
            throw UserNotFoundException(userId)
        }
        
        // Ensure the user object has the correct ID
        val userWithId = user.copy(id = userId)
        return userRepository.update(userWithId)
    }

    suspend fun deleteUser(userId: UserId): Boolean {
        // Check if user exists
        if (!userRepository.exists(userId)) {
            throw UserNotFoundException(userId)
        }
        
        return userRepository.delete(userId)
    }
} 