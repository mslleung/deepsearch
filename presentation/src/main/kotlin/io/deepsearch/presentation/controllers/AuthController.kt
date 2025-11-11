package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.application.services.IAuthService
import io.deepsearch.application.services.IUserService
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.domain.models.valueobjects.Email
import io.deepsearch.domain.models.valueobjects.GoogleUserInfo
import io.deepsearch.domain.models.valueobjects.OAuthProvider
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.services.IJwtService
import io.deepsearch.presentation.dto.LoginRequest
import io.deepsearch.presentation.dto.LoginResponse
import io.deepsearch.presentation.dto.RegisterRequest
import io.deepsearch.presentation.dto.toUserResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AuthController(
    private val authService: IAuthService,
    private val userService: IUserService,
    private val jwtService: IJwtService,
    private val apiKeyService: IApiKeyService,
    private val httpClient: HttpClient
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    suspend fun register(call: ApplicationCall) {
        try {
            val request = call.receive<RegisterRequest>()
            
            val user = authService.registerUser(
                email = Email(request.email),
                password = request.password
            )

            val token = jwtService.generateToken(user.id!!)
            val playgroundKey = apiKeyService.getOrCreatePlaygroundKey(user.id!!)
            
            call.respond(
                HttpStatusCode.Created,
                LoginResponse(
                    token = token,
                    user = user.toUserResponse(),
                    playgroundKey = playgroundKey
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.warn("Bad request in register: {}", e.message, e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            logger.error("Unexpected error in register: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun login(call: ApplicationCall) {
        try {
            val request = call.receive<LoginRequest>()
            
            val user = authService.authenticateUser(
                email = Email(request.email),
                password = request.password
            )
            
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid email or password"))
                return
            }

            val token = jwtService.generateToken(user.id!!)
            val playgroundKey = apiKeyService.getOrCreatePlaygroundKey(user.id!!)
            
            call.respond(
                HttpStatusCode.OK,
                LoginResponse(
                    token = token,
                    user = user.toUserResponse(),
                    playgroundKey = playgroundKey
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.warn("Bad request in login: {}", e.message, e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            logger.error("Unexpected error in login: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun getCurrentUser(call: ApplicationCall) {
        try {
            val principal = call.principal<JWTPrincipal>()
            val userIdValue = principal?.payload?.getClaim(JwtConfig.CLAIM_USER_ID)?.asInt()
            
            if (userIdValue == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                return
            }
            
            val user = userService.getUserById(UserId(userIdValue))
            call.respond(HttpStatusCode.OK, user.toUserResponse())
        } catch (e: Exception) {
            logger.error("Unexpected error in getCurrentUser: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun logout(call: ApplicationCall) {
        // With JWT, logout is typically handled client-side by removing the token
        // For now, just acknowledge the logout
        call.respond(HttpStatusCode.OK, mapOf("message" to "Logged out successfully"))
    }

    suspend fun initiateGoogleOAuth(call: ApplicationCall) {
        // This route is automatically handled by Ktor OAuth plugin
        // It will redirect to Google's authorization page
    }

    suspend fun handleGoogleCallback(call: ApplicationCall) {
        try {
            val principal = call.principal<OAuthAccessTokenResponse.OAuth2>()
            
            if (principal == null) {
                call.respondRedirect("http://localhost:3000/auth/callback#error=OAuth authentication failed")
                return
            }

            // Fetch user info from Google API using the access token
            val userInfo = httpClient.get("https://www.googleapis.com/oauth2/v2/userinfo") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer ${principal.accessToken}")
                }
            }.body<GoogleUserInfo>()

            // Find or create user with OAuth credentials
            val user = authService.findOrCreateOAuthUser(
                provider = OAuthProvider.GOOGLE,
                providerId = userInfo.id,
                email = Email(userInfo.email)
            )

            // Generate JWT token for the user
            val token = jwtService.generateToken(user.id!!)

            // Redirect to frontend with token in hash fragment
            call.respondRedirect("http://localhost:3000/auth/callback#token=$token")
        } catch (e: IllegalStateException) {
            // User with email already exists but with different OAuth provider
            call.respondRedirect("http://localhost:3000/auth/callback#error=${e.message ?: "Account conflict"}")
        } catch (e: Exception) {
            call.application.log.error("OAuth callback error", e)
            call.respondRedirect("http://localhost:3000/auth/callback#error=Internal server error")
        }
    }
}

