package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.UserService
import io.deepsearch.domain.exceptions.UserNotFoundException
import io.deepsearch.domain.valueobjects.UserId
import io.deepsearch.presentation.dto.CreateUserRequest
import io.deepsearch.presentation.dto.UpdateUserRequest
import io.deepsearch.presentation.dto.toDomain
import io.deepsearch.presentation.dto.toResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

class UserController(private val userService: UserService) {
    suspend fun createUser(call: ApplicationCall) {
        try {
            val request = call.receive<CreateUserRequest>()
            val response = userService.createUser(request.toDomain())
            call.respond(HttpStatusCode.Created, response)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun getUserById(call: ApplicationCall) {
        try {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))

            val user = userService.getUserById(UserId(id))
            call.respond(HttpStatusCode.OK, user.toResponse())
        } catch (e: UserNotFoundException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun getAllUsers(call: ApplicationCall) {
        try {
            val users = userService.getAllUsers()
            call.respond(HttpStatusCode.OK, users.map { it.toResponse() })
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun updateUser(call: ApplicationCall) {
        try {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))

            val request = call.receive<UpdateUserRequest>()
            val response = userService.updateUser(UserId(id), request.toDomain(UserId(id)))
            call.respond(HttpStatusCode.OK, response)
        } catch (e: UserNotFoundException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun deleteUser(call: ApplicationCall) {
        try {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))

            val deleted = userService.deleteUser(UserId(id))
            if (deleted) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete user"))
            }
        } catch (e: UserNotFoundException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }
} 