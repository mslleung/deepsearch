package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.SearchFlowEvent
import io.deepsearch.domain.models.entities.SearchFlowEventType
import io.deepsearch.domain.models.valueobjects.QuerySessionId

/**
 * Repository interface for SearchFlowEvent persistence.
 * Used to store timeline events for debugging and visualization.
 */
interface ISearchFlowEventRepository {
    /**
     * Save a new search flow event.
     * 
     * @param event The event to save
     * @return The saved event with generated ID
     */
    suspend fun save(event: SearchFlowEvent): SearchFlowEvent

    /**
     * Save multiple events in a batch.
     * 
     * @param events The events to save
     * @return The saved events with generated IDs
     */
    suspend fun saveAll(events: List<SearchFlowEvent>): List<SearchFlowEvent>

    /**
     * Find all events for a given session, ordered by timestamp.
     * 
     * @param sessionId The session to find events for
     * @return List of events ordered by timestampMs ascending
     */
    suspend fun findBySessionId(sessionId: QuerySessionId): List<SearchFlowEvent>

    /**
     * Find events of a specific type for a session.
     * 
     * @param sessionId The session to find events for
     * @param eventType The type of events to find
     * @return List of matching events ordered by timestampMs
     */
    suspend fun findBySessionIdAndType(
        sessionId: QuerySessionId,
        eventType: SearchFlowEventType
    ): List<SearchFlowEvent>

    /**
     * Count total events for a session.
     * 
     * @param sessionId The session to count events for
     * @return Number of events
     */
    suspend fun countBySessionId(sessionId: QuerySessionId): Long

    /**
     * Delete all events for a session.
     * Used for cleanup during testing or session deletion.
     * 
     * @param sessionId The session to delete events for
     * @return Number of events deleted
     */
    suspend fun deleteBySessionId(sessionId: QuerySessionId): Long
}
