package io.deepsearch.presentation.controllers

import io.deepsearch.application.dto.CreateUserRequest
import io.deepsearch.application.dto.UpdateUserRequest
import io.deepsearch.application.services.UserService
import io.deepsearch.domain.exceptions.UserNotFoundException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.koin.ktor.ext.inject

class UserController {
    suspend fun createUser(call: ApplicationCall) {
        val userService by call.inject<UserService>()
        
        try {
            val request = call.receive<CreateUserRequest>()
            val response = userService.createUser(request)
            call.respond(HttpStatusCode.Created, response)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun getUserById(call: ApplicationCall) {
        val userService by call.inject<UserService>()
        
        try {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
            
            val response = userService.getUserById(id)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: UserNotFoundException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun getAllUsers(call: ApplicationCall) {
        val userService by call.inject<UserService>()
        
        try {
            val response = userService.getAllUsers()
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun updateUser(call: ApplicationCall) {
        val userService by call.inject<UserService>()
        
        try {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
            
            val request = call.receive<UpdateUserRequest>()
            val response = userService.updateUser(id, request)
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
        val userService by call.inject<UserService>()
        
        try {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
            
            val deleted = userService.deleteUser(id)
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