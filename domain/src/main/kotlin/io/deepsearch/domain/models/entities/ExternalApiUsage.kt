package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.SessionId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Represents a single external API call for cost tracking and analysis.
 * 
 * This entity captures usage of third-party APIs like Serper (SERP search),
 * enabling accurate cost calculation for search sessions.
 *
 * @property id Unique identifier for this usage record
 * @property sessionId The session this API call belongs to (query or periodic index)
 * @property apiName Name of the external API (e.g., "SERPER", "GOOGLE_MAPS")
 * @property endpoint The specific endpoint called (e.g., "/search")
 * @property callCount Number of API calls (usually 1, but can batch)
 * @property costUsd Cost in USD for this API call
 * @property query The query string used in the API call (for SERP searches)
 * @property metadata Additional context about the API call
 * @property createdAt Timestamp when the API call was made
 */
@OptIn(ExperimentalTime::class)
data class ExternalApiUsage(
    val id: String,
    val sessionId: SessionId,
    val apiName: String,
    val endpoint: String,
    val callCount: Int = 1,
    val costUsd: Double,
    val query: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant = Clock.System.now()
) {
    companion object {
        // API name constants
        const val API_SERPER = "SERPER"
        
        // Serper endpoints
        const val ENDPOINT_SERPER_SEARCH = "/search"
        
        /**
         * Create a Serper search usage record.
         * 
         * @param id Unique identifier
         * @param sessionId Session this call belongs to
         * @param query The search query
         * @param costUsd Cost in USD ($0.001 per query at standard rate)
         */
        fun serperSearch(
            id: String,
            sessionId: SessionId,
            query: String,
            costUsd: Double = 0.001  // $1/1k queries
        ) = ExternalApiUsage(
            id = id,
            sessionId = sessionId,
            apiName = API_SERPER,
            endpoint = ENDPOINT_SERPER_SEARCH,
            callCount = 1,
            costUsd = costUsd,
            query = query
        )
    }
}
