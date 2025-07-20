package io.deepsearch.domain.repositories

import io.deepsearch.domain.entities.User
import io.deepsearch.domain.valueobjects.UserId

interface UserRepository {
    suspend fun save(user: User): User
    suspend fun findById(id: UserId): User?
    suspend fun findAll(): List<User>
    suspend fun update(user: User): User
    suspend fun delete(id: UserId): Boolean
    suspend fun exists(id: UserId): Boolean
} 