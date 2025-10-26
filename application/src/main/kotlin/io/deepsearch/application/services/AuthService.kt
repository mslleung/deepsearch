package io.deepsearch.application.services

import io.deepsearch.domain.entities.User
import io.deepsearch.domain.models.valueobjects.Email
import io.deepsearch.domain.models.valueobjects.OAuthProvider
import io.deepsearch.domain.models.valueobjects.PasswordHash
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IUserRepository
import kotlinx.datetime.Clock
import org.mindrot.jbcrypt.BCrypt

interface IAuthService {
    suspend fun registerUser(email: Email, password: String, displayName: String? = null): User
    suspend fun authenticateUser(email: Email, password: String): User?
    suspend fun registerOAuthUser(
        provider: OAuthProvider,
        providerId: String,
        email: Email,
        displayName: String?
    ): User
    suspend fun findOrCreateOAuthUser(
        provider: OAuthProvider,
        providerId: String,
        email: Email,
        displayName: String?
    ): User
}

class AuthService(
    private val userRepository: IUserRepository
) : IAuthService {

    override suspend fun registerUser(email: Email, password: String, displayName: String?): User {
        // Check if user already exists
        val existingUser = userRepository.findByEmail(email)
        if (existingUser != null) {
            throw IllegalArgumentException("User with email ${email.value} already exists")
        }

        val passwordHash = hashPassword(password)
        val now = Clock.System.now()

        val user = User(
            email = email,
            passwordHash = passwordHash,
            displayName = displayName,
            createdAt = now,
            updatedAt = now
        )

        return userRepository.save(user)
    }

    override suspend fun authenticateUser(email: Email, password: String): User? {
        val user = userRepository.findByEmail(email) ?: return null

        if (user.passwordHash == null) {
            return null // OAuth-only user
        }

        return if (BCrypt.checkpw(password, user.passwordHash.value)) {
            user
        } else {
            null
        }
    }

    override suspend fun registerOAuthUser(
        provider: OAuthProvider,
        providerId: String,
        email: Email,
        displayName: String?
    ): User {
        // Check if user already exists with this OAuth provider
        val existingUser = userRepository.findByOAuthProvider(provider, providerId)
        if (existingUser != null) {
            throw IllegalArgumentException("User with ${provider.name} account already exists")
        }

        val now = Clock.System.now()

        val user = User(
            email = email,
            oauthProvider = provider,
            oauthProviderId = providerId,
            displayName = displayName,
            createdAt = now,
            updatedAt = now
        )

        return userRepository.save(user)
    }

    override suspend fun findOrCreateOAuthUser(
        provider: OAuthProvider,
        providerId: String,
        email: Email,
        displayName: String?
    ): User {
        // First, try to find by OAuth provider
        val existingUser = userRepository.findByOAuthProvider(provider, providerId)
        if (existingUser != null) {
            return existingUser
        }

        // If not found, try to find by email
        val emailUser = userRepository.findByEmail(email)
        if (emailUser != null) {
            // User exists with this email but different OAuth provider
            // Update user to add OAuth info
            val now = Clock.System.now()
            val updatedUser = emailUser.copy(
                oauthProvider = provider,
                oauthProviderId = providerId,
                displayName = displayName ?: emailUser.displayName,
                updatedAt = now
            )
            return userRepository.update(updatedUser)
        }

        // Create new user
        return registerOAuthUser(provider, providerId, email, displayName)
    }

    private fun hashPassword(password: String): PasswordHash {
        require(password.length >= 8) { "Password must be at least 8 characters long" }
        val hash = BCrypt.hashpw(password, BCrypt.gensalt(12))
        return PasswordHash(hash)
    }
}

