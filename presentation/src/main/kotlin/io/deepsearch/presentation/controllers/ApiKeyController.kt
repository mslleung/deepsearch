package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.presentation.dto.CreateApiKeyRequest
import io.deepsearch.presentation.dto.CreateApiKeyResponse
import io.deepsearch.presentation.dto.toApiKeyResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*

class ApiKeyController(
    private val apiKeyService: IApiKeyService
) {
    suspend fun listKeys(call: ApplicationCall) {
        try {
            val userId = getUserIdFromPrincipal(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return
            }

            val apiKeys = apiKeyService.listUserApiKeys(userId)
            call.respond(HttpStatusCode.OK, apiKeys.map { it.toApiKeyResponse() })
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun createKey(call: ApplicationCall) {
        try {
            val userId = getUserIdFromPrincipal(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return
            }

            val request = call.receive<CreateApiKeyRequest>()
            val (apiKey, rawKey) = apiKeyService.generateApiKey(userId, request.name)

            call.respond(
                HttpStatusCode.Created,
                CreateApiKeyResponse(
                    apiKey = apiKey.toApiKeyResponse(),
                    rawKey = rawKey
                )
            )
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun deleteKey(call: ApplicationCall) {
        try {
            val userId = getUserIdFromPrincipal(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return
            }

            val keyId = call.parameters["id"]?.toIntOrNull()
            if (keyId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid key ID"))
                return
            }

            val deleted = apiKeyService.deleteApiKey(userId, ApiKeyId(keyId))
            if (deleted) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "API key not found or unauthorized"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    private fun getUserIdFromPrincipal(call: ApplicationCall): UserId? {
        val principal = call.principal<JWTPrincipal>()
        val userIdValue = principal?.payload?.getClaim("userId")?.asInt() ?: return null
        return UserId(userIdValue)
    }
}

