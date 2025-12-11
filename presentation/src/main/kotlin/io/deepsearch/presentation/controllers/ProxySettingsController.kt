package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.application.services.IProxySettingsService
import io.deepsearch.domain.models.valueobjects.ProxyRuleId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.proxy.ProxyType
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

class ProxySettingsController(
    private val proxySettingsService: IProxySettingsService,
    private val apiKeyService: IApiKeyService
) {
    suspend fun listRules(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            return
        }

        val rules = proxySettingsService.getRules(userId)
        call.respond(HttpStatusCode.OK, ProxyRulesListResponse(
            rules = rules.map { it.toResponse() }
        ))
    }

    @OptIn(ExperimentalTime::class)
    suspend fun createRule(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            return
        }

        val request = call.receive<CreateProxyRuleRequest>()
        
        // Validate proxy type - throws IllegalArgumentException which StatusPages handles
        val proxyType = parseProxyType(request.proxyType)

        val rule = proxySettingsService.createRule(
            userId = userId,
            urlPattern = request.urlPattern,
            proxyType = proxyType,
            customProxyUrl = request.customProxyUrl
        )
        call.respond(HttpStatusCode.Created, rule.toResponse())
    }

    @OptIn(ExperimentalTime::class)
    suspend fun updateRule(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            return
        }

        val ruleId = call.parameters["id"]?.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid rule ID")

        val request = call.receive<UpdateProxyRuleRequest>()
        
        // Validate proxy type if provided - throws IllegalArgumentException which StatusPages handles
        val proxyType = request.proxyType?.let { parseProxyType(it) }

        val rule = proxySettingsService.updateRule(
            userId = userId,
            ruleId = ProxyRuleId(ruleId),
            urlPattern = request.urlPattern,
            proxyType = proxyType,
            customProxyUrl = request.customProxyUrl
        )
        call.respond(HttpStatusCode.OK, rule.toResponse())
    }

    suspend fun deleteRule(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            return
        }

        val ruleId = call.parameters["id"]?.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid rule ID")

        val deleted = proxySettingsService.deleteRule(userId, ProxyRuleId(ruleId))
        if (deleted) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Proxy rule not found or unauthorized"))
        }
    }

    /**
     * Parses proxy type string to enum.
     * @throws IllegalArgumentException if proxy type is invalid (handled by StatusPages)
     */
    private fun parseProxyType(proxyType: String): ProxyType {
        return try {
            ProxyType.valueOf(proxyType.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid proxy type: $proxyType")
        }
    }

    /**
     * Extract user ID from API key in bearer token.
     * Returns null if API key is missing or invalid.
     */
    private suspend fun getUserIdFromApiKey(call: ApplicationCall): UserId? {
        val principal = call.principal<UserIdPrincipal>()
        val rawApiKey = principal?.name ?: return null

        val isValid = apiKeyService.validateApiKey(rawApiKey)
        if (!isValid) return null

        val apiKey = apiKeyService.getApiKeyByRawKey(rawApiKey) ?: return null
        return apiKey.userId
    }
}

// ==================== Request/Response DTOs ====================

@Serializable
data class CreateProxyRuleRequest(
    val urlPattern: String,
    val proxyType: String,
    val customProxyUrl: String? = null
)

@Serializable
data class UpdateProxyRuleRequest(
    val urlPattern: String? = null,
    val proxyType: String? = null,
    val customProxyUrl: String? = null
)

@Serializable
data class ProxyRuleResponse(
    val id: Long,
    val urlPattern: String,
    val proxyType: String,
    val customProxyUrl: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ProxyRulesListResponse(
    val rules: List<ProxyRuleResponse>
)

@OptIn(ExperimentalTime::class)
private fun io.deepsearch.domain.proxy.ProxyRule.toResponse(): ProxyRuleResponse {
    return ProxyRuleResponse(
        id = id!!.value,
        urlPattern = urlPattern,
        proxyType = proxyType.name,
        customProxyUrl = customProxyUrl,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds()
    )
}
