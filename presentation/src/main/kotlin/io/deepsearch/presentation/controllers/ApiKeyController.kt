package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.ApiKeyType
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.presentation.dto.CreateApiKeyRequest
import io.deepsearch.presentation.dto.CreateApiKeyResponse
import io.deepsearch.presentation.dto.PlaygroundKeyResponse
import io.deepsearch.presentation.dto.toApiKeyResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ApiKeyController(
    private val apiKeyService: IApiKeyService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    suspend fun listKeys(call: ApplicationCall) {
        try {
            val userId = getUserIdFromPrincipal(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return
            }

            val apiKeys = apiKeyService.listUserApiKeys(userId)
                .filter { it.type != ApiKeyType.PLAYGROUND } // Hide playground keys from users
            call.respond(HttpStatusCode.OK, apiKeys.map { it.toApiKeyResponse() })
        } catch (e: Exception) {
            logger.error("Unexpected error in listKeys: {}", e.message, e)
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
            // Users can only create regular keys through the API
            val (apiKey, rawKey) = apiKeyService.generateApiKey(userId, request.name, ApiKeyType.REGULAR)

            call.respond(
                HttpStatusCode.Created,
                CreateApiKeyResponse(
                    apiKey = apiKey.toApiKeyResponse(),
                    rawKey = rawKey
                )
            )
        } catch (e: IllegalStateException) {
            logger.warn("Conflict in createKey: {}", e.message, e)
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Conflict")))
        } catch (e: IllegalArgumentException) {
            logger.warn("Bad request in createKey: {}", e.message, e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            logger.error("Unexpected error in createKey: {}", e.message, e)
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
            logger.error("Unexpected error in deleteKey: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    suspend fun getPlaygroundKey(call: ApplicationCall) {
        try {
            val userId = getUserIdFromPrincipal(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return
            }

            val rawKey = apiKeyService.getOrCreatePlaygroundKey(userId)
            call.respond(HttpStatusCode.OK, PlaygroundKeyResponse(rawKey))
        } catch (e: IllegalStateException) {
            logger.warn("Conflict in getPlaygroundKey: {}", e.message, e)
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Playground key already exists")))
        } catch (e: Exception) {
            logger.error("Unexpected error in getPlaygroundKey: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    private fun getUserIdFromPrincipal(call: ApplicationCall): UserId? {
        val principal = call.principal<JWTPrincipal>()
        val userIdValue = principal?.payload?.getClaim(JwtConfig.CLAIM_USER_ID)?.asInt() ?: return null
        return UserId(userIdValue)
    }
}

