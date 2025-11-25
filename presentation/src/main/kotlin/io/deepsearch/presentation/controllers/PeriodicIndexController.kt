package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IPeriodicIndexService
import io.deepsearch.domain.config.JwtConfig
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

class PeriodicIndexController(
    private val periodicIndexService: IPeriodicIndexService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    suspend fun getConfig(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val config = periodicIndexService.getConfig(userId)
        if (config == null) {
            // Return empty/default response or 404? Let's return 404 or null content.
            // Better to return 204 No Content or 404. 
            // For frontend simplicity, let's return 204.
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.OK, config.toResponse())
        }
    }

    suspend fun saveConfig(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val request = try {
            call.receive<PeriodicIndexConfigRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid request body")
            return
        }

        if (request.url.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "URL is required")
            return
        }

        val config = periodicIndexService.createOrUpdateConfig(userId, request.url, request.periodDays)
        call.respond(HttpStatusCode.OK, config.toResponse())
    }

    suspend fun deleteConfig(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        periodicIndexService.deleteConfig(userId)
        call.respond(HttpStatusCode.NoContent)
    }

    suspend fun triggerNow(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        try {
            val job = periodicIndexService.triggerNow(userId)
            call.respond(HttpStatusCode.OK, job.toResponse())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid request")
        } catch (e: Exception) {
            logger.error("Failed to trigger periodic index for user $userId", e)
            call.respond(HttpStatusCode.InternalServerError, "Failed to trigger index")
        }
    }

    suspend fun getJobHistory(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 10

        val (jobs, total) = periodicIndexService.listJobHistory(userId, page, pageSize)
        
        call.respond(HttpStatusCode.OK, PeriodicIndexJobHistoryResponse(
            jobs = jobs.map { it.toResponse() },
            totalCount = total
        ))
    }

    private fun getUserIdFromJwt(call: ApplicationCall): UserId? {
        val principal = call.principal<JWTPrincipal>()
        val userIdClaim = principal?.payload?.getClaim(JwtConfig.CLAIM_USER_ID)?.asInt()
        return userIdClaim?.let { UserId(it) }
    }
}

