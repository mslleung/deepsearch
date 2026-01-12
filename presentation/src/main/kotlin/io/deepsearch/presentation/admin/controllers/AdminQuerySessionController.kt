package io.deepsearch.presentation.admin.controllers

import io.deepsearch.application.services.ICostCalculationService
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.ISearchFlowEventService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.domain.models.entities.SearchFlowEventType
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.presentation.admin.dto.toAdminDetailDto
import io.deepsearch.presentation.dto.SearchFlowTimelineDto
import io.deepsearch.presentation.dto.TimelineSummaryDto
import io.deepsearch.presentation.dto.toDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

class AdminQuerySessionController(
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService,
    private val searchFlowEventService: ISearchFlowEventService,
    private val costCalculationService: ICostCalculationService
) {

    suspend fun getQuerySessionById(call: ApplicationCall) {
        val sessionIdParam = call.parameters["id"]
        if (sessionIdParam == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Session ID required"))
            return
        }
        val sessionId = QuerySessionId(sessionIdParam)

        val session = querySessionService.getSession(sessionId)

        // Query URL accesses separately
        val urlAccesses = urlAccessService.getUrlAccessesBySession(sessionId)
        
        call.respond(HttpStatusCode.OK, session.toAdminDetailDto(urlAccesses))
    }

    /**
     * Get the search flow timeline for a session, including all events and cost breakdown.
     * Used by the admin UI to visualize the search flow and analyze costs.
     */
    suspend fun getTimeline(call: ApplicationCall) {
        val sessionIdParam = call.parameters["id"]
        if (sessionIdParam == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Session ID required"))
            return
        }
        val sessionId = QuerySessionId(sessionIdParam)

        // Fetch all events for the session
        val events = searchFlowEventService.getEventsForSession(sessionId)
        
        // Calculate costs for the session
        val costs = costCalculationService.calculateSessionCost(sessionId)

        // Build timeline summary
        val eventDtos = events.map { it.toDto() }
        val eventCountsByType = events.groupBy { it.eventType }.mapValues { it.value.size }
            .mapKeys { it.key.name }
        
        // Extract follow-up queries from FOLLOW_UP_QUERY_GENERATED events
        val followUpQueries = events
            .filter { it.eventType == SearchFlowEventType.FOLLOW_UP_QUERY_GENERATED }
            .flatMap { event ->
                @Suppress("UNCHECKED_CAST")
                (event.metadata["followUpQueries"] as? List<String>) ?: emptyList()
            }
            .distinct()

        // Count URL processing events
        val urlsProcessed = events.count { 
            it.eventType == SearchFlowEventType.URL_MARKDOWN_COMPLETE ||
            it.eventType == SearchFlowEventType.URL_PROCESSING_FAILED
        }

        // Count synthesis iterations
        val synthesisIterations = events.count { 
            it.eventType == SearchFlowEventType.SYNTHESIS_COMPLETE 
        }

        val summary = TimelineSummaryDto(
            totalEvents = events.size,
            sessionDurationMs = if (events.isNotEmpty()) {
                events.maxOf { it.timestampMs } - events.minOf { it.timestampMs }
            } else 0L,
            firstEventTimestamp = events.minOfOrNull { it.timestampMs } ?: 0L,
            lastEventTimestamp = events.maxOfOrNull { it.timestampMs } ?: 0L,
            eventCountsByType = eventCountsByType,
            urlsProcessed = urlsProcessed,
            synthesisIterations = synthesisIterations,
            followUpQueries = followUpQueries
        )

        val timeline = SearchFlowTimelineDto(
            sessionId = sessionId.value,
            events = eventDtos,
            costs = costs.toDto(),
            summary = summary
        )

        call.respond(HttpStatusCode.OK, timeline)
    }
}

