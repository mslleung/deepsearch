package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for storing LLM token usage records.
 * Tracks token usage from all LLM API calls for cost analysis.
 */
class LlmTokenUsageTable(
    private val querySessionTable: QuerySessionTable
) : Table("llm_token_usage") {
    val id = varchar("id", 255)
    val querySessionId = varchar("query_session_id", 255).references(querySessionTable.id).nullable()
    val agentName = varchar("agent_name", 255)
    val modelName = varchar("model_name", 255)
    val promptTokens = integer("prompt_tokens")
    val outputTokens = integer("output_tokens")
    val totalTokens = integer("total_tokens")
    val createdAtEpochMs = long("created_at_epoch_ms")

    override val primaryKey = PrimaryKey(id)
}

