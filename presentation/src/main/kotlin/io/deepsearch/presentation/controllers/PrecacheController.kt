package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IPrecacheService
import io.deepsearch.application.services.PrecacheEvent
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
        val baseUrl = call.request.queryParameters["url"]
        val maxUrlCount = call.request.queryParameters["maxUrlCount"]?.toIntOrNull() ?: 100

        if (baseUrl.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing 'url' parameter")
            return
        }

        val events = precacheService.startOrAttach(baseUrl, maxUrlCount)
        try {
            events.collect { event: PrecacheEvent ->
                val payload = Json.encodeToString(PrecacheEvent.serializer(), event)
                sse.send(ServerSentEvent(payload))
            }
        } catch (e : Throwable) {
            // Client disconnected; process continues server-side
            logger.warn("Client disconnected. {}", e.message)
        }
    }
}


