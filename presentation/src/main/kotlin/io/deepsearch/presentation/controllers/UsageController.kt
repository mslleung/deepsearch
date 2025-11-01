package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.application.services.IUserSubscriptionService
import io.deepsearch.application.services.IUsageService
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.domain.models.entities.SubscriptionPlan
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.presentation.dto.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class UsageController(
    private val subscriptionPlanService: IUserSubscriptionService,
    private val usageService: IUsageService,
    private val apiKeyService: IApiKeyService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    suspend fun getCurrentUsage(call: ApplicationCall) {
        try {
            val userId = getUserIdFromJwt(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return
            }

            val usableSubscription = subscriptionPlanService.getUsableUserSubscription(userId)
            val allActiveSubscriptions = subscriptionPlanService.getUserAllActiveSubscriptions(userId)
            
            val subscriptionUsage = usageService.getActiveSubscriptionUsage(userId)

            val usableSubscriptionDto = usableSubscription?.toDto()
            val allSubscriptionsDto = allActiveSubscriptions.map { it.toDto() }

            val response = CurrentUsageResponse(
                usableSubscription = usableSubscriptionDto,
                allActiveSubscriptions = allSubscriptionsDto,
                totalUsed = subscriptionUsage.totalUsed,
                totalAvailable = subscriptionUsage.totalAvailable,
                hasRemainingSearches = subscriptionPlanService.checkUsageLimit(userId)
            )

            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            logger.error("Unexpected error in getCurrentUsage: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun getUsageStats(call: ApplicationCall) {
        try {
            val userId = getUserIdFromJwt(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return
            }

            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
            if (days !in listOf(7, 30)) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Days parameter must be 7 or 30"))
                return
            }

            val stats = usageService.getUserUsageStats(userId, days)

            val response = UsageStatsResponse(
                dailyUsage = stats.dailyUsage,
                totalUsage = stats.totalUsage,
                periodDays = days
            )

            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            logger.error("Unexpected error in getUsageStats: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun getApiKeyUsageStats(call: ApplicationCall) {
        try {
            val userId = getUserIdFromJwt(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return
            }

            val apiKeyId = call.parameters["keyId"]?.toIntOrNull()
            if (apiKeyId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid API key ID"))
                return
            }

            // Verify the API key belongs to the user
            val apiKey = apiKeyService.getApiKeyById(ApiKeyId(apiKeyId))
            if (apiKey == null || apiKey.userId != userId) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                return
            }

            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
            if (days !in listOf(7, 30)) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Days parameter must be 7 or 30"))
                return
            }

            val stats = usageService.getApiKeyUsageStats(ApiKeyId(apiKeyId), days)

            val response = ApiKeyUsageStatsResponse(
                apiKeyId = apiKeyId,
                apiKeyName = apiKey.name,
                dailyUsage = stats.dailyUsage,
                totalUsage = stats.totalUsage,
                periodDays = days
            )

            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            logger.error("Unexpected error in getApiKeyUsageStats: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun getAvailablePlans(call: ApplicationCall) {
        try {
            val plans = SubscriptionPlan.entries
            val plansDto = plans.map { it.toDto() }
            call.respond(HttpStatusCode.OK, plansDto)
        } catch (e: Exception) {
            logger.error("Unexpected error in getAvailablePlans: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun upgradePlan(call: ApplicationCall) {
        try {
            val userId = getUserIdFromJwt(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                return
            }

            val request = call.receive<UpgradePlanRequest>()
            val plan = SubscriptionPlan.fromName(request.planName)
            
            if (plan == null || plan == SubscriptionPlan.FREE) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid plan name"))
                return
            }

            val newSubscription = subscriptionPlanService.upgradePlan(userId, plan)
            val subscriptionDto = newSubscription.toDto()
            call.respond(HttpStatusCode.OK, subscriptionDto)
        } catch (e: IllegalArgumentException) {
            logger.warn("Bad request in upgradePlan: {}", e.message, e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            logger.error("Unexpected error in upgradePlan: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    private fun getUserIdFromJwt(call: ApplicationCall): UserId? {
        val principal = call.principal<JWTPrincipal>()
        val userIdClaim = principal?.payload?.getClaim(JwtConfig.CLAIM_USER_ID)?.asInt()
        return userIdClaim?.let { UserId(it) }
    }
}


