package io.deepsearch.presentation

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.presentation.config.presentationModule
import io.deepsearch.presentation.routes.*
import io.ktor.http.*
import io.ktor.server.sse.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureSerialization()
    configureDependencyInjection()
    configureAuthentication()
    configureWebSockets()
    configureSSE()
    configureRequestValidation()

    configureAuthRoutes()
    configureApiKeyRoutes()
    configureSearchRoutes()
    configurePrecacheRoutes()
    configureCacheRoutes()
}

private fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json()
    }
}

private fun Application.configureDependencyInjection() {
    install(Koin) {
        slf4jLogger()
        modules(
            presentationModule,
            module {
                single {
                    JwtConfig(
                        publicKey = environment.config.property("jwt.publicKey").getString(),
                        privateKey = environment.config.property("jwt.privateKey").getString(),
                        issuer = environment.config.property("jwt.issuer").getString(),
                        audience = environment.config.property("jwt.audience").getString(),
                        realm = environment.config.property("jwt.realm").getString()
                    )
                }
            })
    }
}

private fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
}

private fun Application.configureSSE() {
    install(SSE) {
    }
}

private fun Application.configureRequestValidation() {
    install(RequestValidation) {
        validate<String> { bodyText ->
            if (bodyText.isNotBlank()) {
                ValidationResult.Valid
            } else {
                ValidationResult.Invalid("Body text cannot be empty")
            }
        }
    }
}

private fun Application.configureAuthentication() {
    val application = this
    install(Authentication) {
        configureJwtAuth(application)
        configureApiKeyAuth()
    }
}

private fun AuthenticationConfig.configureJwtAuth(application: Application) {
    jwt("auth-jwt") {
        // cannot DI here because not initialized yet
        val jwtConfig = JwtConfig(
            publicKey = application.environment.config.property("jwt.publicKey").getString(),
            privateKey = application.environment.config.property("jwt.privateKey").getString(),
            issuer = application.environment.config.property("jwt.issuer").getString(),
            audience = application.environment.config.property("jwt.audience").getString(),
            realm = application.environment.config.property("jwt.realm").getString()
        )

        realm = jwtConfig.realm

        val keyFactory = KeyFactory.getInstance("EC")
        val publicKeySpec = X509EncodedKeySpec(Base64.decode(jwtConfig.publicKey))
        val publicKey = keyFactory.generatePublic(publicKeySpec) as ECPublicKey
        val algorithm = Algorithm.ECDSA256(publicKey, null)
        verifier(
            JWT.require(algorithm)
                .withAudience(jwtConfig.audience)
                .withIssuer(jwtConfig.issuer)
                .build()
        )

        validate { credential ->
            if (credential.payload.claims.contains(JwtConfig.CLAIM_USER_ID)) {
                JWTPrincipal(credential.payload)
            } else {
                null
            }
        }

        challenge { defaultScheme, realm ->
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or expired token"))
        }
    }
}

private fun AuthenticationConfig.configureApiKeyAuth() {
    bearer("api-key") {
        // Bearer authenticate block is synchronous and doesn't have access to call context
        // We'll validate API keys in route handlers where we have access to suspend functions
        // For now, just pass through the token
        authenticate { credential ->
            // Return a principal with the raw token
            // Actual validation happens in route handlers
            UserIdPrincipal(credential.token)
        }
    }
}
