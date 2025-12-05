package io.deepsearch.presentation

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.genai.Client
import io.deepsearch.domain.config.ApiKeyConfig
import io.deepsearch.domain.config.DatabaseEncryptionConfig
import io.deepsearch.domain.config.EnvironmentConfig
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.domain.config.OAuthConfig
import io.deepsearch.domain.config.PostgresConfig
import io.deepsearch.domain.config.SerperConfig
import io.deepsearch.domain.config.GoogleOAuthConfig
import io.deepsearch.domain.config.StripeConfig
import io.deepsearch.presentation.config.configureStatusPages
import io.deepsearch.presentation.config.presentationModule
import io.deepsearch.presentation.routes.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.*
import io.ktor.server.sse.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.OAuthServerSettings
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.oauth
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.event.Level
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureCallId()
    configureCallLogging()
    configureSerialization()
    configureCORS()
    configureDependencyInjection()
    configureStatusPages()
    configureAuthentication()
    configureWebSockets()
    configureSSE()
    configureRequestValidation()

    // web app routes (requires jwt auth)
    configureAuthRoutes()
    configureApiKeyRoutes()
    configureUsageRoutes()
    configureQuerySessionRoutes()
    configurePaymentRoutes()

    // API routes (requires api key)
    configureSearchRoutes()
    configurePeriodicIndexJobRoutes()
    configurePeriodicIndexRoutes()
}

private fun Application.configureCallId() {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotEmpty() }
    }
}

private fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
    }
}

private fun Application.configureSerialization() {
    install(ServerContentNegotiation) {
        json()
    }
}

private fun Application.configureCORS() {
    install(CORS) {
        allowHost("localhost:3000")
        allowHost("127.0.0.1:3000")
        
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.CacheControl)
        allowHeader("X-Requested-With")

        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Patch)
        
        allowCredentials = true
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
                single {
                    OAuthConfig(
                        google = GoogleOAuthConfig(
                            clientId = environment.config.property("oauth.google.clientId").getString(),
                            clientSecret = environment.config.property("oauth.google.clientSecret").getString(),
                            redirectUrl = environment.config.property("oauth.google.redirectUrl").getString()
                        )
                    )
                }
                single {
                    ApiKeyConfig(
                        hmacSecret = environment.config.property("apiKey.hmacSecret").getString()
                    )
                }
                single {
                    DatabaseEncryptionConfig(
                        encryptionSecret = environment.config.property("database.encryptionSecret").getString()
                    )
                }
                single {
                    SerperConfig(
                        apiKey = environment.config.property("serper.apiKey").getString()
                    )
                }
                single {
                    if (environment.config.property("ktor.development").getString().toBoolean()) {
                        Client.builder()
                            .apiKey(environment.config.property("gemini.apiKey").getString())
                            .build()
                    } else {
                        Client.builder()
                            .project(environment.config.property("vertexai.projectId").getString())
                            .location(environment.config.property("vertexai.location").getString())
                            .vertexAI(true)
                            .build()
                    }
                }
                single {
                    PostgresConfig(
                        host = environment.config.property("database.postgres.host").getString(),
                        port = environment.config.property("database.postgres.port").getString().toInt(),
                        database = environment.config.property("database.postgres.database").getString(),
                        username = environment.config.property("database.postgres.username").getString(),
                        password = environment.config.property("database.postgres.password").getString()
                    )
                }
                single {
                    EnvironmentConfig(
                        isDevelopmentMode = environment.config.property("ktor.development").getString().toBoolean()
                    )
                }
                single {
                    StripeConfig(
                        secretKey = environment.config.property("stripe.secretKey").getString(),
                        publishableKey = environment.config.property("stripe.publishableKey").getString(),
                        webhookSecret = environment.config.property("stripe.webhookSecret").getString(),
                        frontendUrl = environment.config.property("stripe.frontendUrl").getString()
                    )
                }
                single {
                    HttpClient(OkHttp) {
                        install(ClientContentNegotiation) {
                            json(Json {
                                ignoreUnknownKeys = true
                            })
                        }
                    }
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
        configureOAuthGoogle(application)
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

private fun AuthenticationConfig.configureOAuthGoogle(application: Application) {
    oauth("auth-oauth-google") {
        urlProvider = {
            application.environment.config.property("oauth.google.redirectUrl").getString()
        }
        
        providerLookup = {
            OAuthServerSettings.OAuth2ServerSettings(
                name = "google",
                authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
                accessTokenUrl = "https://accounts.google.com/o/oauth2/token",
                requestMethod = HttpMethod.Post,
                clientId = application.environment.config.property("oauth.google.clientId").getString(),
                clientSecret = application.environment.config.property("oauth.google.clientSecret").getString(),
                defaultScopes = listOf(
                    "https://www.googleapis.com/auth/userinfo.profile",
                    "https://www.googleapis.com/auth/userinfo.email"
                )
            )
        }
        
        client = HttpClient(OkHttp) {
            install(ClientContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
        }
    }
}
