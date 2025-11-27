package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.LlmTokenUsage
import io.deepsearch.domain.models.valueobjects.QuerySessionId

/**
 * Repository for persisting and retrieving LLM token usage records.
 */
interface ILlmTokenUsageRepository {
    /**
     * Save a new token usage record.
     */
    suspend fun save(usage: LlmTokenUsage): LlmTokenUsage
    
    /**
     * Find all token usage records for a given query session.
     */
    suspend fun findBySessionId(sessionId: QuerySessionId): List<LlmTokenUsage>
    
    /**
     * Get total token usage statistics for a query session.
     */
    suspend fun getTotalTokensBySessionId(sessionId: QuerySessionId): TokenUsageSummary
}

/**
 * Summary of token usage for a session.
 */
data class TokenUsageSummary(
    val totalPromptTokens: Long,
    val totalOutputTokens: Long,
    val totalTokens: Long,
    val callCount: Long
)

