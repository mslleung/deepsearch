package io.deepsearch.application.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import io.deepsearch.domain.models.valueobjects.UserId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

interface IJwtService {
    fun generateToken(userId: UserId): String
    fun validateToken(token: String): DecodedJWT?
    fun getUserIdFromToken(token: String): UserId?
}

class JwtService(
    private val secret: String,
    private val issuer: String = "deepsearch",
    private val audience: String = "deepsearch-users",
    private val expirationDays: Int = 7
) : IJwtService {

    private val algorithm = Algorithm.HMAC256(secret)

    override fun generateToken(userId: UserId): String {
        val now = Clock.System.now()
        val expiration = now.plus(expirationDays.days)

        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId.value)
            .withIssuedAt(java.util.Date(now.toEpochMilliseconds()))
            .withExpiresAt(java.util.Date(expiration.toEpochMilliseconds()))
            .sign(algorithm)
    }

    override fun validateToken(token: String): DecodedJWT? {
        return try {
            val verifier = JWT.require(algorithm)
                .withAudience(audience)
                .withIssuer(issuer)
                .build()
            verifier.verify(token)
        } catch (e: Exception) {
            null
        }
    }

    override fun getUserIdFromToken(token: String): UserId? {
        val decoded = validateToken(token) ?: return null
        val userIdValue = decoded.getClaim("userId").asInt() ?: return null
        return UserId(userIdValue)
    }
}

