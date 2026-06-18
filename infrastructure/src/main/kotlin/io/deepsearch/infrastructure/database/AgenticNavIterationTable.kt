package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Stores one row per iteration of the agentic navigation loop.
 *
 * Each row captures the agent's observation, decision, and serialized actions,
 * plus timing. Screenshot GCS paths are stored in the child [AgenticNavScreenshotTable].
 */
class AgenticNavIterationTable : Table("agentic_nav_iterations") {
    val id = long("id").autoIncrement()
    val sessionId = varchar("session_id", 255)
    val url = text("url")
    val iteration = integer("iteration")
    val observation = text("observation").nullable()
    val decision = varchar("decision", 50)
    val actionsJson = text("actions_json").default("[]")
    val durationMs = long("duration_ms").nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, sessionId)
        uniqueIndex(sessionId, url, iteration)
    }
}
