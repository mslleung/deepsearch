package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for storing search flow events.
 * These events capture the timeline of operations during a search session
 * for debugging and visualization purposes.
 */
class SearchFlowEventsTable : Table("search_flow_events") {
    val id = long("id").autoIncrement()
    val sessionId = varchar("session_id", 36).index()
    val eventType = varchar("event_type", 50)
    val timestampMs = long("timestamp_ms")
    val durationMs = long("duration_ms").nullable()
    val url = text("url").nullable()
    val query = text("query").nullable()
    val title = text("title").nullable()
    val description = text("description").nullable()
    val metadata = text("metadata").default("{}")
    val createdAtEpochMs = long("created_at_epoch_ms")

    override val primaryKey = PrimaryKey(id)

    init {
        // Index for fast session lookups with timestamp ordering
        index(isUnique = false, sessionId, timestampMs)
        // Index for filtering by event type
        index(isUnique = false, sessionId, eventType)
    }
}
