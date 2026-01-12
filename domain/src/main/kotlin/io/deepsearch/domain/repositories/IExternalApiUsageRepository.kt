package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.ExternalApiUsage
import io.deepsearch.domain.models.valueobjects.SessionId

/**
 * Repository interface for ExternalApiUsage persistence.
 * Used to track third-party API calls (Serper, etc.) for cost calculation.
 */
interface IExternalApiUsageRepository {
    /**
     * Save a new external API usage record.
     * 
     * @param usage The usage record to save
     * @return The saved record
     */
    suspend fun save(usage: ExternalApiUsage): ExternalApiUsage

    /**
     * Find all external API usage records for a given session.
     * 
     * @param sessionId The session to find usage for
     * @return List of usage records ordered by createdAt
     */
    suspend fun findBySessionId(sessionId: SessionId): List<ExternalApiUsage>

    /**
     * Find all external API usage records for a specific API in a session.
     * 
     * @param sessionId The session to find usage for
     * @param apiName The API name (e.g., "SERPER")
     * @return List of usage records for the specified API
     */
    suspend fun findBySessionIdAndApiName(sessionId: SessionId, apiName: String): List<ExternalApiUsage>

    /**
     * Get total cost for a session's external API usage.
     * 
     * @param sessionId The session to calculate cost for
     * @return Total cost in USD
     */
    suspend fun getTotalCostBySessionId(sessionId: SessionId): Double

    /**
     * Get summary of external API usage for a session.
     * 
     * @param sessionId The session to get summary for
     * @return Summary with call counts and costs by API
     */
    suspend fun getSummaryBySessionId(sessionId: SessionId): ExternalApiUsageSummary

    /**
     * Count total API calls for a session.
     * 
     * @param sessionId The session to count calls for
     * @return Number of API calls
     */
    suspend fun countBySessionId(sessionId: SessionId): Long

    /**
     * Delete all usage records for a session.
     * 
     * @param sessionId The session to delete records for
     * @return Number of records deleted
     */
    suspend fun deleteBySessionId(sessionId: SessionId): Long
}

/**
 * Summary of external API usage for a session.
 */
data class ExternalApiUsageSummary(
    val totalCalls: Int,
    val totalCostUsd: Double,
    val byApi: Map<String, ApiUsageStats>
)

/**
 * Usage statistics for a specific API.
 */
data class ApiUsageStats(
    val apiName: String,
    val callCount: Int,
    val totalCostUsd: Double
)
