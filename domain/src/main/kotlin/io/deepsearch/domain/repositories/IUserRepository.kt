package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.User
import io.deepsearch.domain.models.valueobjects.Email
import io.deepsearch.domain.models.valueobjects.OAuthProvider
import io.deepsearch.domain.models.valueobjects.UserId

interface IUserRepository {
    suspend fun save(user: User): User
    suspend fun findById(id: UserId): User?
    suspend fun findByEmail(email: Email): User?
    suspend fun findByOAuthProvider(provider: OAuthProvider, providerId: String): User?
    suspend fun findByStripeCustomerId(stripeCustomerId: String): User?
    suspend fun findAll(): List<User>
    suspend fun update(user: User): User
    suspend fun delete(id: UserId): Boolean
    suspend fun exists(id: UserId): Boolean
}