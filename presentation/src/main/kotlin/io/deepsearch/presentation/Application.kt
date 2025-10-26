package io.deepsearch.presentation

import io.deepsearch.presentation.config.presentationModule
import io.deepsearch.presentation.routes.*
import io.ktor.server.sse.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.websocket.*
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
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
    // configureUserRoutes()  // Disabled - replaced by AuthController
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
        modules(presentationModule)
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
    install(Authentication) {
        configureJwtAuth()
        configureApiKeyAuth()
    }
}

private fun AuthenticationConfig.configureJwtAuth() {
    jwt("auth-jwt") {
        val jwtSecret = System.getenv("JWT_SECRET") ?: "default-secret-please-change-in-production"
        val jwtIssuer = "deepsearch"
        val jwtAudience = "deepsearch-users"
        
        verifier(
            com.auth0.jwt.JWT
                .require(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtSecret))
                .withAudience(jwtAudience)
                .withIssuer(jwtIssuer)
                .build()
        )
        
        validate { credential ->
            if (credential.payload.audience.contains(jwtAudience)) {
                io.ktor.server.auth.jwt.JWTPrincipal(credential.payload)
            } else {
                null
            }
        }
        
        challenge { _, _ ->
            call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or expired token"))
        }
    }
}

private fun AuthenticationConfig.configureApiKeyAuth() {
    bearer("api-key") {
        authenticate { credential ->
            val apiKeyService = call.scope.get<io.deepsearch.application.services.IApiKeyService>()
            val apiKey = apiKeyService.validateApiKey(credential.token)
            
            if (apiKey != null) {
                io.ktor.server.auth.UserIdPrincipal(apiKey.userId.value.toString())
            } else {
                null
            }
        }
        
        challenge { _, _ ->
            call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing API key"))
        }
    }
}
