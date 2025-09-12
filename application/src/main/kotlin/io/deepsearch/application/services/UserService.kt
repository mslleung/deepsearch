package io.deepsearch.application.services

import io.deepsearch.domain.entities.User
import io.deepsearch.domain.repositories.IUserRepository
import io.deepsearch.domain.exceptions.UserNotFoundException
import io.deepsearch.domain.models.valueobjects.UserId

interface IUserService {
    suspend fun createUser(user: User): UserId
    suspend fun getUserById(userId: UserId): User
    suspend fun getAllUsers(): List<User>
    suspend fun updateUser(userId: UserId, user: User): User
    suspend fun deleteUser(userId: UserId): Boolean
}

class UserService(
    private val userRepository: IUserRepository
): IUserService {
    override suspend fun createUser(user: User): UserId {
        val savedUser = userRepository.save(user)
        return savedUser.id!!
    }

    override suspend fun getUserById(userId: UserId): User {
        val user = userRepository.findById(userId)
            ?: throw UserNotFoundException(userId)
        return user
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
        val userWithId = user.copy(id = userId)
        return userRepository.update(userWithId)
    }

    override suspend fun deleteUser(userId: UserId): Boolean {
        // Check if user exists
        if (!userRepository.exists(userId)) {
            throw UserNotFoundException(userId)
        }
        
        return userRepository.delete(userId)
    }
} 