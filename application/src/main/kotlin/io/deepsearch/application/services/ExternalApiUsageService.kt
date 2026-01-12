package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.ExternalApiUsage
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.ExternalApiUsageSummary
import io.deepsearch.domain.repositories.IExternalApiUsageRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Interface for tracking external API usage for cost calculation.
 */
interface IExternalApiUsageService {
    /**
     * Record a Serper API search call.
     * 
     * @param sessionId The session making the API call
     * @param query The search query
     * @param costUsd Cost in USD (default: $0.001 for standard pricing)
     */
    suspend fun recordSerperSearch(
        sessionId: SessionId,
        query: String,
        costUsd: Double = SERPER_COST_PER_QUERY
    )

    /**
     * Get all API usage records for a session.
     * 
     * @param sessionId The session to get usage for
     * @return List of usage records
     */
    suspend fun getUsageBySession(sessionId: SessionId): List<ExternalApiUsage>

    /**
     * Get summary of API usage and costs for a session.
     * 
     * @param sessionId The session to get summary for
     * @return Usage summary with costs by API
     */
    suspend fun getSummaryBySession(sessionId: SessionId): ExternalApiUsageSummary

    /**
     * Get total external API cost for a session in USD.
     * 
     * @param sessionId The session to calculate cost for
     * @return Total cost in USD
     */
    suspend fun getTotalCostBySession(sessionId: SessionId): Double

    companion object {
        // Serper pricing: $1.00 per 1,000 queries = $0.001 per query
        const val SERPER_COST_PER_QUERY = 0.001
    }
}

/**
 * Service for tracking external API usage (Serper, etc.) for cost calculation.
 * 
 * Provides methods to retrieve usage summaries for cost reporting.
 */
class ExternalApiUsageService(
    private val repository: IExternalApiUsageRepository
) : IExternalApiUsageService {

    private val logger = LoggerFactory.getLogger(ExternalApiUsageService::class.java)

    override suspend fun recordSerperSearch(
        sessionId: SessionId,
        query: String,
        costUsd: Double
    ) {
        withContext(NonCancellable) {
            try {
                val usage = ExternalApiUsage.serperSearch(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    query = query,
                    costUsd = costUsd
                )
                repository.save(usage)
                logger.debug("[${sessionId.value}] Recorded Serper search: query='$query', cost=$$costUsd")
            } catch (e: Exception) {
                logger.error("[${sessionId.value}] Failed to record Serper usage for query: $query", e)
            }
        }
    }

    override suspend fun getUsageBySession(sessionId: SessionId): List<ExternalApiUsage> {
        return repository.findBySessionId(sessionId)
    }

    override suspend fun getSummaryBySession(sessionId: SessionId): ExternalApiUsageSummary {
        return repository.getSummaryBySessionId(sessionId)
    }

    override suspend fun getTotalCostBySession(sessionId: SessionId): Double {
        return repository.getTotalCostBySessionId(sessionId)
    }
}
