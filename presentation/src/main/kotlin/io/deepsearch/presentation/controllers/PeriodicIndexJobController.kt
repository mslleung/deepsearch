package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.application.services.IPeriodicIndexJobService
import io.deepsearch.domain.models.entities.PeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.presentation.dto.toResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PeriodicIndexJobController(
    private val periodicIndexJobService: IPeriodicIndexJobService,
    private val apiKeyService: IApiKeyService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Stream periodic index job events via SSE.
     * Auth via query param apiKey since EventSource cannot set headers.
     */
    suspend fun stream(call: ApplicationCall, sse: ServerSSESession) {
        // Validate API key from query param (EventSource cannot set headers)
        val rawApiKey = call.request.queryParameters["apiKey"]
        if (rawApiKey == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing apiKey parameter"))
            return
        }

        val isValid = apiKeyService.validateApiKey(rawApiKey)
        if (!isValid) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid API key"))
            return
        }

        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'id' parameter"))
            return
        }

        val events = periodicIndexJobService.events(jobId)
        try {
            events.collect { event: IPeriodicIndexJobService.PeriodicIndexEvent ->
                val payload = Json.encodeToString(IPeriodicIndexJobService.PeriodicIndexEvent.serializer(), event)
                sse.send(ServerSentEvent(payload))
            }
        } catch (e: Throwable) {
            // Client disconnected; process continues server-side
            logger.warn("Client disconnected. {}", e.message)
        }
    }

    suspend fun stop(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid API key"))
            return
        }

        val jobId = call.parameters["id"]?.toLongOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid jobId"))
        periodicIndexJobService.stop(jobId)
        call.respond(HttpStatusCode.NoContent)
    }

    suspend fun list(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid API key"))
            return
        }

        val stateParam = call.request.queryParameters["state"]
        val state = stateParam?.let { runCatching { PeriodicIndexJobState.valueOf(it) }.getOrNull() }
        val jobs = periodicIndexJobService.list(state)
        call.respond(jobs.map { it.toResponse() })
    }

    /**
     * Extract user ID from API key in bearer token.
     * Returns null if API key is missing or invalid.
     */
    private suspend fun getUserIdFromApiKey(call: ApplicationCall): UserId? {
        val principal = call.principal<UserIdPrincipal>()
        val rawApiKey = principal?.name ?: return null

        val isValid = apiKeyService.validateApiKey(rawApiKey)
        if (!isValid) {
            return null
        }

        val apiKey = apiKeyService.getApiKeyByRawKey(rawApiKey) ?: return null
        return apiKey.userId
    }
}

