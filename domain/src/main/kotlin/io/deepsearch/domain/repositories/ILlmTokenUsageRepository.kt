package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.LlmTokenUsage
import io.deepsearch.domain.models.valueobjects.SessionId

/**
 * Repository for persisting and retrieving LLM token usage records.
 */
interface ILlmTokenUsageRepository {
    /**
     * Save a new token usage record.
     */
    suspend fun save(usage: LlmTokenUsage): LlmTokenUsage
    
    /**
     * Find all token usage records for a given session.
     */
    suspend fun findBySessionId(sessionId: SessionId): List<LlmTokenUsage>
    
    /**
     * Get total token usage statistics for a session.
     */
    suspend fun getTotalTokensBySessionId(sessionId: SessionId): TokenUsageSummary
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

