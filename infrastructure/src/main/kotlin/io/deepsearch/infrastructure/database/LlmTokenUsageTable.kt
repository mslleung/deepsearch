package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for storing LLM token usage records.
 * Tracks token usage from all LLM API calls for cost analysis.
 * 
 * Uses a unified session_id column with storage string format:
 * - For query sessions: "query:xxx"
 * - For periodic index jobs: "periodic:123"
 * - For batch periodic index jobs: "batch:456"
 * 
 * This is consistent with ExternalApiUsageTable's approach.
 */
class LlmTokenUsageTable : Table("llm_token_usage") {
    val id = varchar("id", 255)
    val sessionId = varchar("session_id", 255).index()
    val agentName = varchar("agent_name", 255)
    val modelName = varchar("model_name", 255)
    val promptTokens = integer("prompt_tokens")
    val outputTokens = integer("output_tokens")
    val totalTokens = integer("total_tokens")
    val createdAtEpochMs = long("created_at_epoch_ms")

    override val primaryKey = PrimaryKey(id)
}
