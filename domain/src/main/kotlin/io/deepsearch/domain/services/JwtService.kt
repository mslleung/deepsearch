package io.deepsearch.domain.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.domain.models.valueobjects.UserId
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

interface IJwtService {
    fun generateToken(userId: UserId): String
}

@OptIn(ExperimentalTime::class)
class JwtService(
    private val jwtConfig: JwtConfig
) : IJwtService {

    override fun generateToken(userId: UserId): String {
        val now = Clock.System.now()
        val expiration = now.plus(30.days)

        val keyFactory = KeyFactory.getInstance("EC")

        val privateKeySpec = PKCS8EncodedKeySpec(Base64.decode(jwtConfig.privateKey))
        val privateKey = keyFactory.generatePrivate(privateKeySpec) as ECPrivateKey

        val publicKeySpec = X509EncodedKeySpec(Base64.decode(jwtConfig.publicKey))
        val publicKey = keyFactory.generatePublic(publicKeySpec) as ECPublicKey

        return JWT.create()
            .withAudience(jwtConfig.audience)
            .withIssuer(jwtConfig.issuer)
            .withClaim(JwtConfig.CLAIM_USER_ID, userId.value)
            .withIssuedAt(Date(now.toEpochMilliseconds()))
            .withExpiresAt(Date(expiration.toEpochMilliseconds()))
            .sign(Algorithm.ECDSA256(publicKey, privateKey))
    }
}

