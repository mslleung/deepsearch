package io.deepsearch.application.services

import io.deepsearch.domain.entities.User
import io.deepsearch.domain.models.valueobjects.Email
import io.deepsearch.domain.models.valueobjects.OAuthProvider
import io.deepsearch.domain.models.valueobjects.PasswordHash
import io.deepsearch.domain.repositories.IUserRepository
import org.mindrot.jbcrypt.BCrypt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface IAuthService {
    suspend fun registerUser(email: Email, password: String): User
    suspend fun authenticateUser(email: Email, password: String): User?
    suspend fun registerOAuthUser(
        provider: OAuthProvider,
        providerId: String,
        email: Email
    ): User
    suspend fun findOrCreateOAuthUser(
        provider: OAuthProvider,
        providerId: String,
        email: Email
    ): User
}

@OptIn(ExperimentalTime::class)
class AuthService(
    private val userRepository: IUserRepository
) : IAuthService {

    override suspend fun registerUser(email: Email, password: String): User {
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
            createdAt = now,
            updatedAt = now
        )

        return userRepository.save(user)
    }

    override suspend fun authenticateUser(email: Email, password: String): User? {
        val user = userRepository.findByEmail(email) ?: return null

        val passwordHash = user.passwordHash ?: return null // OAuth-only user

        return if (BCrypt.checkpw(password, passwordHash.value)) {
            user
        } else {
            null
        }
    }

    override suspend fun registerOAuthUser(
        provider: OAuthProvider,
        providerId: String,
        email: Email
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
            createdAt = now,
            updatedAt = now
        )

        return userRepository.save(user)
    }

    override suspend fun findOrCreateOAuthUser(
        provider: OAuthProvider,
        providerId: String,
        email: Email
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
            // Note: This is a business decision - we're merging accounts based on email.
            // In production, you might want to ask for user confirmation first.
            throw IllegalStateException("User with email ${email.value} already exists. Cannot link OAuth account automatically.")
        }

        // Create new user
        return registerOAuthUser(provider, providerId, email)
    }

    private fun hashPassword(password: String): PasswordHash {
        require(password.length >= 8) { "Password must be at least 8 characters long" }
        val hash = BCrypt.hashpw(password, BCrypt.gensalt(12))
        return PasswordHash(hash)
    }
}

