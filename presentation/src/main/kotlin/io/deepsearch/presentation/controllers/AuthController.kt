package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IAuthService
import io.deepsearch.application.services.IJwtService
import io.deepsearch.application.services.IUserService
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.presentation.dto.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*

class AuthController(
    private val authService: IAuthService,
    private val userService: IUserService,
    private val jwtService: IJwtService
) {
    suspend fun register(call: ApplicationCall) {
        try {
            val request = call.receive<RegisterRequest>()
            
            val user = authService.registerUser(
                email = request.toEmail(),
                password = request.password,
                displayName = request.displayName
            )
            
            val token = jwtService.generateToken(user.id!!)
            
            call.respond(
                HttpStatusCode.Created,
                LoginResponse(
                    token = token,
                    user = user.toUserResponse()
                )
            )
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun login(call: ApplicationCall) {
        try {
            val request = call.receive<LoginRequest>()
            
            val user = authService.authenticateUser(
                email = request.toEmail(),
                password = request.password
            )
            
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid email or password"))
                return
            }
            
            val token = jwtService.generateToken(user.id!!)
            
            call.respond(
                HttpStatusCode.OK,
                LoginResponse(
                    token = token,
                    user = user.toUserResponse()
                )
            )
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun getCurrentUser(call: ApplicationCall) {
        try {
            val principal = call.principal<JWTPrincipal>()
            val userIdValue = principal?.payload?.getClaim("userId")?.asInt()
            
            if (userIdValue == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                return
            }
            
            val user = userService.getUserById(UserId(userIdValue))
            call.respond(HttpStatusCode.OK, user.toUserResponse())
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun logout(call: ApplicationCall) {
        // With JWT, logout is typically handled client-side by removing the token
        // For now, just acknowledge the logout
        call.respond(HttpStatusCode.OK, mapOf("message" to "Logged out successfully"))
    }
}

