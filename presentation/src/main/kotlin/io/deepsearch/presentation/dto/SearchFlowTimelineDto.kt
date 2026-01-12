package io.deepsearch.presentation.dto

import io.deepsearch.domain.models.entities.SearchFlowEvent
import io.deepsearch.domain.models.valueobjects.SessionCostSummary
import io.deepsearch.domain.models.valueobjects.LlmCostBreakdown
import io.deepsearch.domain.models.valueobjects.ExternalApiCostBreakdown
import io.deepsearch.domain.models.valueobjects.ModelCost
import io.deepsearch.domain.models.valueobjects.AgentCost
import io.deepsearch.domain.models.valueobjects.ApiCost
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for a complete search flow timeline, including events and cost breakdown.
 * Used by the admin UI to visualize the search flow and analyze costs.
 */
@Serializable
data class SearchFlowTimelineDto(
    val sessionId: String,
    val events: List<SearchFlowEventDto>,
    val costs: SessionCostSummaryDto,
    val summary: TimelineSummaryDto
)

/**
 * DTO for a single search flow event in the timeline.
 */
@Serializable
data class SearchFlowEventDto(
    val id: Long,
    val eventType: String,
    val timestampMs: Long,
    val durationMs: Long? = null,
    val url: String? = null,
    val query: String? = null,
    val title: String? = null,
    val description: String? = null,
    val metadata: Map<String, String> = emptyMap()  // Serialized as string map for JSON
)

/**
 * DTO for timeline summary statistics.
 */
@Serializable
data class TimelineSummaryDto(
    val totalEvents: Int,
    val sessionDurationMs: Long,
    val firstEventTimestamp: Long,
    val lastEventTimestamp: Long,
    val eventCountsByType: Map<String, Int>,
    val urlsProcessed: Int,
    val synthesisIterations: Int,
    val followUpQueries: List<String>
)

/**
 * DTO for complete session cost summary.
 */
@Serializable
data class SessionCostSummaryDto(
    val totalCostUsd: Double,
    val llmCosts: LlmCostBreakdownDto,
    val externalApiCosts: ExternalApiCostBreakdownDto
)

/**
 * DTO for LLM token usage and cost breakdown.
 */
@Serializable
data class LlmCostBreakdownDto(
    val totalPromptTokens: Int,
    val totalOutputTokens: Int,
    val totalCostUsd: Double,
    val byModel: List<ModelCostDto>,
    val byAgent: List<AgentCostDto>
)

/**
 * DTO for cost by LLM model.
 */
@Serializable
data class ModelCostDto(
    val modelName: String,
    val promptTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
    val callCount: Int
)

/**
 * DTO for cost by agent/service.
 */
@Serializable
data class AgentCostDto(
    val agentName: String,
    val promptTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
    val callCount: Int
)

/**
 * DTO for external API usage and cost breakdown.
 */
@Serializable
data class ExternalApiCostBreakdownDto(
    val totalCalls: Int,
    val totalCostUsd: Double,
    val byApi: List<ApiCostDto>
)

/**
 * DTO for cost by external API.
 */
@Serializable
data class ApiCostDto(
    val apiName: String,
    val callCount: Int,
    val costUsd: Double
)

// Extension functions to convert domain objects to DTOs

fun SearchFlowEvent.toDto(): SearchFlowEventDto {
    // Convert metadata Map<String, Any> to Map<String, String> for JSON serialization
    val stringMetadata = metadata.mapValues { (_, value) ->
        when (value) {
            is List<*> -> value.joinToString(", ")
            else -> value.toString()
        }
    }
    
    return SearchFlowEventDto(
        id = id,
        eventType = eventType.name,
        timestampMs = timestampMs,
        durationMs = durationMs,
        url = url,
        query = query,
        title = title,
        description = description,
        metadata = stringMetadata
    )
}

fun SessionCostSummary.toDto(): SessionCostSummaryDto = SessionCostSummaryDto(
    totalCostUsd = totalCostUsd,
    llmCosts = llmCosts.toDto(),
    externalApiCosts = externalApiCosts.toDto()
)

fun LlmCostBreakdown.toDto(): LlmCostBreakdownDto = LlmCostBreakdownDto(
    totalPromptTokens = totalPromptTokens,
    totalOutputTokens = totalOutputTokens,
    totalCostUsd = totalCostUsd,
    byModel = byModel.values.map { it.toDto() },
    byAgent = byAgent.values.map { it.toDto() }
)

fun ModelCost.toDto(): ModelCostDto = ModelCostDto(
    modelName = modelName,
    promptTokens = promptTokens,
    outputTokens = outputTokens,
    costUsd = costUsd,
    callCount = callCount
)

fun AgentCost.toDto(): AgentCostDto = AgentCostDto(
    agentName = agentName,
    promptTokens = promptTokens,
    outputTokens = outputTokens,
    costUsd = costUsd,
    callCount = callCount
)

fun ExternalApiCostBreakdown.toDto(): ExternalApiCostBreakdownDto = ExternalApiCostBreakdownDto(
    totalCalls = totalCalls,
    totalCostUsd = totalCostUsd,
    byApi = byApi.values.map { it.toDto() }
)

fun ApiCost.toDto(): ApiCostDto = ApiCostDto(
    apiName = apiName,
    callCount = callCount,
    costUsd = costUsd
)
