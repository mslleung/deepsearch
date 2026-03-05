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

/**
 * Convert a SearchFlowEvent sealed class instance to its DTO representation.
 * Extracts type-specific fields into the generic DTO format for JSON serialization.
 */
fun SearchFlowEvent.toDto(): SearchFlowEventDto {
    val metadata = mutableMapOf<String, String>()
    var url: String? = null
    var query: String? = null
    var title: String? = null
    var description: String? = null
    var durationMs: Long? = null
    
    // Extract type-specific fields
    when (this) {
        is SearchFlowEvent.SessionStarted -> {
            url = this.url
            query = this.query
            metadata["mode"] = this.mode
        }
        is SearchFlowEvent.SessionError -> {
            metadata["errorType"] = this.errorType
            metadata["errorMessage"] = this.errorMessage
            this.errorCategory?.let { metadata["errorCategory"] = it }
            this.affectedUrl?.let { url = it; metadata["affectedUrl"] = it }
            this.technicalDetails?.let { metadata["technicalDetails"] = it }
        }
        is SearchFlowEvent.DiscoverySerpComplete -> {
            query = this.query
            durationMs = this.durationMs
            metadata["linksFound"] = this.linksFound.toString()
        }
        is SearchFlowEvent.UrlProcessingStarted -> {
            url = this.url
        }
        is SearchFlowEvent.UrlLinkDiscoveryComplete -> {
            url = this.url
        }
        is SearchFlowEvent.UrlMarkdownComplete -> {
            url = this.url
            title = this.title
            description = this.description
            metadata["markdownLength"] = this.markdownLength.toString()
            metadata["accessType"] = this.accessType
            metadata["wasCached"] = this.wasCached.toString()
        }
        is SearchFlowEvent.UrlProcessingFailed -> {
            url = this.url
            metadata["errorMessage"] = this.errorMessage
        }
        is SearchFlowEvent.SourcesEvaluated -> {
            metadata["processedUrlCount"] = this.processedUrlCount.toString()
            metadata["relevantCount"] = this.relevantCount.toString()
            metadata["isGoodEnough"] = this.isGoodEnough.toString()
            this.reason?.let { metadata["reason"] = it }
        }
        is SearchFlowEvent.SynthesisComplete -> {
            metadata["iterationNumber"] = this.iterationNumber.toString()
            metadata["sourceCount"] = this.sourceCount.toString()
            metadata["status"] = this.status
            metadata["followUpQueries"] = this.followUpQueries.joinToString(", ")
        }
        is SearchFlowEvent.AnswerChunk -> {
            metadata["chunk"] = this.chunk
        }
        is SearchFlowEvent.FollowUpQueryGenerated -> {
            metadata["followUpQueries"] = this.followUpQueries.joinToString(", ")
            this.whatsMissing?.let { metadata["whatsMissing"] = it }
            metadata["iterationNumber"] = this.iterationNumber.toString()
        }
        // Events with no additional data
        is SearchFlowEvent.SessionCompleted,
        is SearchFlowEvent.SessionTimeout,
        is SearchFlowEvent.QueryProcessingStarted,
        is SearchFlowEvent.QueryProcessingComplete,
        is SearchFlowEvent.DiscoveryStarted,
        is SearchFlowEvent.DiscoveryHybridComplete,
        is SearchFlowEvent.DiscoveryKgComplete,
        is SearchFlowEvent.DiscoveryFileSearchComplete,
        is SearchFlowEvent.SynthesisStarted -> { /* No additional metadata */ }
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
        metadata = metadata
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
