package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.User
import io.deepsearch.domain.repositories.IUserRepository
import io.deepsearch.domain.exceptions.UserNotFoundException
import io.deepsearch.domain.models.valueobjects.Email
import io.deepsearch.domain.models.valueobjects.UserId

interface IUserService {
    suspend fun getUserById(userId: UserId): User
    suspend fun getUserByEmail(email: Email): User?
    suspend fun getAllUsers(): List<User>
    suspend fun updateUser(userId: UserId, user: User): User
    suspend fun deleteUser(userId: UserId): Boolean
}

class UserService(
    private val userRepository: IUserRepository
): IUserService {
    override suspend fun getUserById(userId: UserId): User {
        val user = userRepository.findById(userId)
            ?: throw UserNotFoundException(userId)
        return user
    }

    override suspend fun getUserByEmail(email: Email): User? {
        return userRepository.findByEmail(email)
    }

    override suspend fun getAllUsers(): List<User> {
        return userRepository.findAll()
    }

    override suspend fun updateUser(userId: UserId, user: User): User {
        // Check if user exists
        if (!userRepository.exists(userId)) {
            throw UserNotFoundException(userId)
        }
        
        // Ensure the user object has the correct ID
        user.id = userId
        return userRepository.update(user)
    }

    override suspend fun deleteUser(userId: UserId): Boolean {
        // Check if user exists
        if (!userRepository.exists(userId)) {
            throw UserNotFoundException(userId)
        }
        
        return userRepository.delete(userId)
    }
} 