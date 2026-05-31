package io.deepsearch.presentation.admin.controllers

import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.application.services.IUsageService
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.ApiKeyType
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IApiKeyRepository
import io.deepsearch.domain.repositories.IUserRepository
import io.deepsearch.presentation.admin.dto.CreateApiKeyRequest
import io.deepsearch.presentation.admin.dto.toAdminDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

class AdminApiKeyController(
    private val apiKeyRepository: IApiKeyRepository,
    private val apiKeyService: IApiKeyService,
    private val userRepository: IUserRepository,
    private val usageService: IUsageService
) {

    suspend fun createApiKey(call: ApplicationCall) {
        val request = call.receive<CreateApiKeyRequest>()

        val type = try {
            ApiKeyType.fromString(request.type)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid API key type: ${request.type}"))
            return
        }

        val user = userRepository.findById(UserId(request.userId))
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
            return
        }

        try {
            val (apiKey, rawKey) = apiKeyService.generateApiKey(UserId(request.userId), request.name, type)
            call.respond(
                HttpStatusCode.Created,
                mapOf("rawKey" to rawKey, "apiKey" to apiKey.toAdminDto())
            )
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message))
        }
    }

    suspend fun getAllApiKeys(call: ApplicationCall) {
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
    }

    suspend fun getApiKeyById(call: ApplicationCall) {
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
    }

    suspend fun revokeApiKey(call: ApplicationCall) {
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
    }

    suspend fun restoreApiKey(call: ApplicationCall) {
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
    }

    suspend fun getApiKeyUsageStats(call: ApplicationCall) {
        val keyId = call.parameters["id"]?.toIntOrNull()
        if (keyId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid API key ID"))
            return
        }

        val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
        
        val stats = usageService.getApiKeyUsageStats(ApiKeyId(keyId), days)
        call.respond(HttpStatusCode.OK, stats)
    }
}

