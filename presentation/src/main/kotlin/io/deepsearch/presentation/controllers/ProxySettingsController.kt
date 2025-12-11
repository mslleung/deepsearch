package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IProxySettingsService
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.domain.models.valueobjects.ProxyRuleId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.proxy.ProxyType
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

class ProxySettingsController(
    private val proxySettingsService: IProxySettingsService
) {
    suspend fun listRules(call: ApplicationCall) {
        val userId = getUserIdFromPrincipal(call)
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
        val userId = getUserIdFromPrincipal(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            return
        }

        val request = call.receive<CreateProxyRuleRequest>()
        
        // Validate proxy type
        val proxyType = try {
            ProxyType.valueOf(request.proxyType.uppercase())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid proxy type: ${request.proxyType}"))
            return
        }

        try {
            val rule = proxySettingsService.createRule(
                userId = userId,
                urlPattern = request.urlPattern,
                proxyType = proxyType,
                customProxyUrl = request.customProxyUrl
            )
            call.respond(HttpStatusCode.Created, rule.toResponse())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message))
        }
    }

    @OptIn(ExperimentalTime::class)
    suspend fun updateRule(call: ApplicationCall) {
        val userId = getUserIdFromPrincipal(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            return
        }

        val ruleId = call.parameters["id"]?.toLongOrNull()
        if (ruleId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid rule ID"))
            return
        }

        val request = call.receive<UpdateProxyRuleRequest>()
        
        // Validate proxy type if provided
        val proxyType = request.proxyType?.let { 
            try {
                ProxyType.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid proxy type: $it"))
                return
            }
        }

        try {
            val rule = proxySettingsService.updateRule(
                userId = userId,
                ruleId = ProxyRuleId(ruleId),
                urlPattern = request.urlPattern,
                proxyType = proxyType,
                customProxyUrl = request.customProxyUrl
            )
            call.respond(HttpStatusCode.OK, rule.toResponse())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: IllegalAccessError) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
        }
    }

    suspend fun deleteRule(call: ApplicationCall) {
        val userId = getUserIdFromPrincipal(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            return
        }

        val ruleId = call.parameters["id"]?.toLongOrNull()
        if (ruleId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid rule ID"))
            return
        }

        val deleted = proxySettingsService.deleteRule(userId, ProxyRuleId(ruleId))
        if (deleted) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Proxy rule not found or unauthorized"))
        }
    }

    private fun getUserIdFromPrincipal(call: ApplicationCall): UserId? {
        val principal = call.principal<JWTPrincipal>()
        val userIdValue = principal?.payload?.getClaim(JwtConfig.CLAIM_USER_ID)?.asInt() ?: return null
        return UserId(userIdValue)
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

