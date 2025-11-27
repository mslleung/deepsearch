package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IPeriodicIndexJobService
import io.deepsearch.domain.models.entities.PeriodicIndexJobState
import io.deepsearch.presentation.dto.PeriodicIndexJobStartRequest
import io.deepsearch.presentation.dto.toResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PeriodicIndexJobController(private val periodicIndexJobService: IPeriodicIndexJobService) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    suspend fun stream(call: ApplicationCall, sse: ServerSSESession) {
        val jobId = call.parameters["id"]?.toLongOrNull()

        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing 'id' parameter")
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
        val jobId = call.parameters["jobId"]?.toLongOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, "Invalid jobId")
        periodicIndexJobService.stop(jobId)
        call.respond(HttpStatusCode.NoContent)
    }

    suspend fun list(call: ApplicationCall) {
        val stateParam = call.request.queryParameters["state"]
        val state = stateParam?.let { runCatching { PeriodicIndexJobState.valueOf(it) }.getOrNull() }
        val jobs = periodicIndexJobService.list(state)
        call.respond(jobs.map { it.toResponse() })
    }
}

