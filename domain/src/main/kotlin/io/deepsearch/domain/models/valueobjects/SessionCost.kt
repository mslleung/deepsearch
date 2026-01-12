package io.deepsearch.domain.models.valueobjects

/**
 * Complete cost summary for a search session, including both LLM and external API costs.
 *
 * @property llmCosts Breakdown of LLM token usage and costs
 * @property externalApiCosts Breakdown of external API usage and costs (Serper, etc.)
 * @property totalCostUsd Total cost in USD for the entire session
 */
data class SessionCostSummary(
    val llmCosts: LlmCostBreakdown,
    val externalApiCosts: ExternalApiCostBreakdown,
    val totalCostUsd: Double
) {
    companion object {
        fun empty() = SessionCostSummary(
            llmCosts = LlmCostBreakdown.empty(),
            externalApiCosts = ExternalApiCostBreakdown.empty(),
            totalCostUsd = 0.0
        )
    }
}

/**
 * Breakdown of LLM token usage and costs for a session.
 *
 * @property totalPromptTokens Total input tokens across all LLM calls
 * @property totalOutputTokens Total output tokens across all LLM calls
 * @property totalCostUsd Total LLM cost in USD
 * @property byModel Cost breakdown by model name
 * @property byAgent Cost breakdown by agent name
 */
data class LlmCostBreakdown(
    val totalPromptTokens: Int,
    val totalOutputTokens: Int,
    val totalCostUsd: Double,
    val byModel: Map<String, ModelCost>,
    val byAgent: Map<String, AgentCost>
) {
    companion object {
        fun empty() = LlmCostBreakdown(
            totalPromptTokens = 0,
            totalOutputTokens = 0,
            totalCostUsd = 0.0,
            byModel = emptyMap(),
            byAgent = emptyMap()
        )
    }
}

/**
 * Cost breakdown for a specific LLM model.
 *
 * @property modelName Name of the model (e.g., "gemini-2.5-flash-lite")
 * @property promptTokens Total input tokens for this model
 * @property outputTokens Total output tokens for this model
 * @property costUsd Total cost in USD for this model
 * @property callCount Number of API calls to this model
 */
data class ModelCost(
    val modelName: String,
    val promptTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
    val callCount: Int
)

/**
 * Cost breakdown for a specific agent/service.
 *
 * @property agentName Name of the agent (e.g., "StreamingAnswerSynthesisAgent")
 * @property promptTokens Total input tokens for this agent
 * @property outputTokens Total output tokens for this agent
 * @property costUsd Total cost in USD for this agent
 * @property callCount Number of LLM calls by this agent
 */
data class AgentCost(
    val agentName: String,
    val promptTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
    val callCount: Int
)

/**
 * Breakdown of external API usage and costs for a session.
 *
 * @property totalCalls Total number of external API calls
 * @property totalCostUsd Total cost in USD for external APIs
 * @property byApi Cost breakdown by API name
 */
data class ExternalApiCostBreakdown(
    val totalCalls: Int,
    val totalCostUsd: Double,
    val byApi: Map<String, ApiCost>
) {
    companion object {
        fun empty() = ExternalApiCostBreakdown(
            totalCalls = 0,
            totalCostUsd = 0.0,
            byApi = emptyMap()
        )
    }
}

/**
 * Cost breakdown for a specific external API.
 *
 * @property apiName Name of the API (e.g., "SERPER")
 * @property callCount Number of API calls
 * @property costUsd Total cost in USD for this API
 */
data class ApiCost(
    val apiName: String,
    val callCount: Int,
    val costUsd: Double
)
