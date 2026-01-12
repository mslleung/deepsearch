package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.SearchFlowEvent
import io.deepsearch.domain.models.entities.SearchFlowEventType
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.repositories.ISearchFlowEventRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Interface for SearchFlowEventService.
 * Provides methods to emit events (persist + map) and retrieve events for timeline.
 */
interface ISearchFlowEventService {
    /**
     * Emit an event - persists to DB and returns mapped SearchEvent for SSE.
     * Called by orchestrator instead of directly sending to channel.
     * 
     * @param event The SearchFlowEvent to emit
     * @return The mapped SearchEvent for SSE, or null if this event type doesn't have an SSE equivalent
     */
    suspend fun emit(event: SearchFlowEvent): SearchEvent?

    /**
     * Get all events for a session (for timeline API).
     * 
     * @param sessionId The session to get events for
     * @return List of events ordered by timestamp
     */
    suspend fun getEventsForSession(sessionId: QuerySessionId): List<SearchFlowEvent>

    /**
     * Get events of a specific type for a session.
     * 
     * @param sessionId The session to get events for
     * @param eventType The type of events to retrieve
     * @return List of matching events ordered by timestamp
     */
    suspend fun getEventsByType(
        sessionId: QuerySessionId,
        eventType: SearchFlowEventType
    ): List<SearchFlowEvent>

    /**
     * Get timeline summary statistics for a session.
     * 
     * @param sessionId The session to get stats for
     * @return Timeline statistics including total events, duration, etc.
     */
    suspend fun getTimelineStats(sessionId: QuerySessionId): TimelineStats
}

/**
 * Summary statistics for a session timeline.
 */
data class TimelineStats(
    val totalEvents: Long,
    val sessionDurationMs: Long?,
    val firstEventTimestamp: Long?,
    val lastEventTimestamp: Long?,
    val eventCountsByType: Map<SearchFlowEventType, Int>
)

/**
 * Service that handles SearchFlowEvent emission, persistence, and mapping.
 * 
 * This is the central service for the unified event system:
 * 1. Orchestrator calls emit() with SearchFlowEvent
 * 2. Event is persisted for timeline visualization
 * 3. Mapped SearchEvent is returned for SSE streaming
 */
class SearchFlowEventService(
    private val repository: ISearchFlowEventRepository,
    private val mapper: ISearchFlowEventMapper
) : ISearchFlowEventService {

    private val logger = LoggerFactory.getLogger(SearchFlowEventService::class.java)

    override suspend fun emit(event: SearchFlowEvent): SearchEvent? {
        // 1. Persist to database (NonCancellable ensures completion even if caller is cancelled)
        withContext(NonCancellable) {
            try {
                repository.save(event)
            } catch (e: Exception) {
                logger.error("[${event.sessionId.value}] Failed to persist SearchFlowEvent: ${event.eventType}", e)
            }
        }

        // 2. Map to SearchEvent for SSE (returns null if no SSE equivalent)
        return mapper.toStreamingSearchEndpointEvent(event)
    }

    override suspend fun getEventsForSession(sessionId: QuerySessionId): List<SearchFlowEvent> {
        return repository.findBySessionId(sessionId)
    }

    override suspend fun getEventsByType(
        sessionId: QuerySessionId,
        eventType: SearchFlowEventType
    ): List<SearchFlowEvent> {
        return repository.findBySessionIdAndType(sessionId, eventType)
    }

    override suspend fun getTimelineStats(sessionId: QuerySessionId): TimelineStats {
        val events = repository.findBySessionId(sessionId)
        
        if (events.isEmpty()) {
            return TimelineStats(
                totalEvents = 0,
                sessionDurationMs = null,
                firstEventTimestamp = null,
                lastEventTimestamp = null,
                eventCountsByType = emptyMap()
            )
        }

        val firstTimestamp = events.minOf { it.timestampMs }
        val lastTimestamp = events.maxOf { it.timestampMs }
        val eventCountsByType = events.groupBy { it.eventType }.mapValues { it.value.size }

        return TimelineStats(
            totalEvents = events.size.toLong(),
            sessionDurationMs = lastTimestamp - firstTimestamp,
            firstEventTimestamp = firstTimestamp,
            lastEventTimestamp = lastTimestamp,
            eventCountsByType = eventCountsByType
        )
    }
}
