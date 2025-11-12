package io.deepsearch.presentation.admin.controllers

import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.application.services.IUsageService
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IApiKeyRepository
import io.deepsearch.domain.repositories.IUserRepository
import io.deepsearch.presentation.admin.dto.toAdminDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

class AdminApiKeyController(
    private val apiKeyRepository: IApiKeyRepository,
    private val apiKeyService: IApiKeyService,
    private val userRepository: IUserRepository,
    private val usageService: IUsageService
) {

    suspend fun getAllApiKeys(call: ApplicationCall) {
        try {
            val userId = call.request.queryParameters["userId"]?.toIntOrNull()
            val includeDeleted = call.request.queryParameters["includeDeleted"]?.toBoolean() ?: false
            
            val apiKeys = if (userId != null) {
                if (includeDeleted) {
                    apiKeyRepository.findByUserIdIncludingDeleted(UserId(userId))
                } else {
                    apiKeyRepository.findByUserId(UserId(userId))
                }
            } else {
                // Get all API keys for all users
                if (includeDeleted) {
                    apiKeyRepository.findAllIncludingDeleted()
                } else {
                    val users = userRepository.findAll()
                    users.flatMap { user ->
                        apiKeyRepository.findByUserId(user.id!!)
                    }
                }
            }
            
            val apiKeysDto = apiKeys.map { it.toAdminDto() }
            call.respond(HttpStatusCode.OK, apiKeysDto)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun getApiKeyById(call: ApplicationCall) {
        try {
            val keyId = call.parameters["id"]?.toIntOrNull()
            if (keyId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid API key ID"))
                return
            }

            // Admins can view deleted keys
            val apiKey = apiKeyRepository.findByIdIncludingDeleted(ApiKeyId(keyId))
            if (apiKey == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "API key not found"))
                return
            }

            call.respond(HttpStatusCode.OK, apiKey.toAdminDto())
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun revokeApiKey(call: ApplicationCall) {
        try {
            val keyId = call.parameters["id"]?.toIntOrNull()
            if (keyId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid API key ID"))
                return
            }

            val apiKey = apiKeyRepository.findByIdIncludingDeleted(ApiKeyId(keyId))
            if (apiKey == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "API key not found"))
                return
            }

            if (apiKey.isDeleted()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "API key is already deleted"))
                return
            }

            // Perform soft delete
            apiKey.softDelete()
            apiKeyRepository.update(apiKey)
            call.respond(HttpStatusCode.OK, mapOf("message" to "API key revoked successfully"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun restoreApiKey(call: ApplicationCall) {
        try {
            val keyId = call.parameters["id"]?.toIntOrNull()
            if (keyId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid API key ID"))
                return
            }

            val apiKey = apiKeyRepository.findByIdIncludingDeleted(ApiKeyId(keyId))
            if (apiKey == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "API key not found"))
                return
            }

            if (!apiKey.isDeleted()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "API key is not deleted"))
                return
            }

            // Restore the API key
            apiKey.restore()
            apiKeyRepository.update(apiKey)
            call.respond(HttpStatusCode.OK, mapOf("message" to "API key restored successfully"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun getApiKeyUsageStats(call: ApplicationCall) {
        try {
            val keyId = call.parameters["id"]?.toIntOrNull()
            if (keyId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid API key ID"))
                return
            }

            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
            
            val stats = usageService.getApiKeyUsageStats(ApiKeyId(keyId), days)
            call.respond(HttpStatusCode.OK, stats)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }
}

