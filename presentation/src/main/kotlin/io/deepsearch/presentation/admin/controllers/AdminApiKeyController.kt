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
            
            val apiKeys = if (userId != null) {
                apiKeyRepository.findByUserId(UserId(userId))
            } else {
                // Get all API keys for all users
                // Since there's no findAll method, we'll need to iterate through users
                val users = userRepository.findAll()
                users.flatMap { user ->
                    apiKeyRepository.findByUserId(user.id!!)
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

            val apiKey = apiKeyService.getApiKeyById(ApiKeyId(keyId))
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

            val apiKey = apiKeyService.getApiKeyById(ApiKeyId(keyId))
            if (apiKey == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "API key not found"))
                return
            }

            val deleted = apiKeyRepository.delete(ApiKeyId(keyId))
            if (deleted) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "API key revoked successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "API key not found"))
            }
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

