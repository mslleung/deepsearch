package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for storing external API usage records.
 * Tracks third-party API calls (Serper, etc.) for cost calculation and analysis.
 * 
 * Supports both query sessions and periodic index jobs:
 * - For query sessions: session_id contains "query:xxx"
 * - For periodic index jobs: session_id contains "periodic:xxx"
 */
class ExternalApiUsageTable : Table("external_api_usage") {
    val id = varchar("id", 36)
    val sessionId = varchar("session_id", 255).index()
    val apiName = varchar("api_name", 50)
    val endpoint = varchar("endpoint", 255)
    val callCount = integer("call_count").default(1)
    val costUsd = double("cost_usd")
    val query = text("query").nullable()
    val metadata = text("metadata").default("{}")
    val createdAtEpochMs = long("created_at_epoch_ms")

    override val primaryKey = PrimaryKey(id)

    init {
        // Index for fast session lookups
        index(isUnique = false, sessionId, apiName)
    }
}
