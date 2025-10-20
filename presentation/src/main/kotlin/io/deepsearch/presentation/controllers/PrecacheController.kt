package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IPrecacheService
import io.deepsearch.domain.models.entities.PrecacheJobState
import io.deepsearch.presentation.dto.toResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PrecacheController(private val precacheService: IPrecacheService) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    suspend fun stream(call: ApplicationCall, sse: ServerSSESession) {
        val jobId = call.request.queryParameters["jobId"]?.toLongOrNull()

        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing 'jobId' parameter")
            return
        }

        val events = precacheService.events(jobId)
        try {
            events.collect { event: IPrecacheService.PrecacheEvent ->
                val payload = Json.encodeToString(IPrecacheService.PrecacheEvent.serializer(), event)
                sse.send(ServerSentEvent(payload))
            }
        } catch (e: Throwable) {
            // Client disconnected; process continues server-side
            logger.warn("Client disconnected. {}", e.message)
        }
    }

    suspend fun start(call: ApplicationCall) {
        val baseUrl = call.request.queryParameters["url"]
        val maxUrlCount = call.request.queryParameters["maxUrlCount"]?.toIntOrNull() ?: 100
        if (baseUrl.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing 'url' parameter")
            return
        }

        if (maxUrlCount !in 1..1000) {
            call.respond(HttpStatusCode.BadRequest, "Max url count must be within [1, 1000]. ${maxUrlCount}")
        }
        val job = precacheService.start(baseUrl, maxUrlCount)
        call.respond(HttpStatusCode.OK, job.toResponse())
    }

    suspend fun stop(call: ApplicationCall) {
        val jobId = call.parameters["jobId"]?.toLongOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, "Invalid jobId")
        precacheService.stop(jobId)
        call.respond(HttpStatusCode.NoContent)
    }

    suspend fun list(call: ApplicationCall) {
        val stateParam = call.request.queryParameters["state"]
        val state = stateParam?.let { runCatching { PrecacheJobState.valueOf(it) }.getOrNull() }
        val jobs = precacheService.list(state)
        call.respond(jobs.map { it.toResponse() })
    }
}


