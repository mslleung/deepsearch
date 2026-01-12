package io.deepsearch.application.services

import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.models.entities.LlmTokenUsage
import io.deepsearch.domain.models.valueobjects.*
import io.deepsearch.domain.repositories.IExternalApiUsageRepository
import io.deepsearch.domain.repositories.ILlmTokenUsageRepository

/**
 * Interface for calculating search session costs.
 */
interface ICostCalculationService {
    /**
     * Calculate the complete cost summary for a session.
     * Includes both LLM token costs and external API costs.
     * 
     * @param sessionId The session to calculate costs for
     * @return Complete cost summary with breakdowns
     */
    suspend fun calculateSessionCost(sessionId: SessionId): SessionCostSummary

    /**
     * Calculate LLM token cost for a single usage record.
     * 
     * @param usage The token usage record
     * @return Cost in USD
     */
    fun calculateTokenCost(usage: LlmTokenUsage): Double

    /**
     * Get the pricing for a specific model.
     * 
     * @param modelName The model name
     * @return Pair of (inputPricePerMillion, outputPricePerMillion) in USD
     */
    fun getModelPricing(modelName: String): Pair<Double, Double>
}

/**
 * Service for calculating search session costs based on LLM token usage and external API calls.
 * 
 * Pricing sources:
 * - Gemini API: https://ai.google.dev/gemini-api/docs/pricing (via ModelIds enum)
 * - Serper API: https://serper.dev/ ($1/1k queries)
 */
class CostCalculationService(
    private val llmTokenUsageRepository: ILlmTokenUsageRepository,
    private val externalApiUsageRepository: IExternalApiUsageRepository
) : ICostCalculationService {

    override suspend fun calculateSessionCost(sessionId: SessionId): SessionCostSummary {
        val llmCosts = calculateLlmCosts(sessionId)
        val externalApiCosts = calculateExternalApiCosts(sessionId)
        val totalCost = llmCosts.totalCostUsd + externalApiCosts.totalCostUsd

        return SessionCostSummary(
            llmCosts = llmCosts,
            externalApiCosts = externalApiCosts,
            totalCostUsd = totalCost
        )
    }

    override fun calculateTokenCost(usage: LlmTokenUsage): Double {
        val (inputPrice, outputPrice) = getModelPricing(usage.modelName)
        val inputCost = (usage.promptTokens / 1_000_000.0) * inputPrice
        val outputCost = (usage.outputTokens / 1_000_000.0) * outputPrice
        return inputCost + outputCost
    }

    override fun getModelPricing(modelName: String): Pair<Double, Double> {
        return ModelIds.getPricing(modelName)
    }

    private suspend fun calculateLlmCosts(sessionId: SessionId): LlmCostBreakdown {
        val usageRecords = llmTokenUsageRepository.findBySessionId(sessionId)

        if (usageRecords.isEmpty()) {
            return LlmCostBreakdown.empty()
        }

        // Calculate totals
        val totalPromptTokens = usageRecords.sumOf { it.promptTokens }
        val totalOutputTokens = usageRecords.sumOf { it.outputTokens }

        // Calculate cost by model
        val byModel = usageRecords
            .groupBy { it.modelName }
            .mapValues { (modelName, records) ->
                val promptTokens = records.sumOf { it.promptTokens }
                val outputTokens = records.sumOf { it.outputTokens }
                val cost = records.sumOf { calculateTokenCost(it) }
                ModelCost(
                    modelName = modelName,
                    promptTokens = promptTokens,
                    outputTokens = outputTokens,
                    costUsd = cost,
                    callCount = records.size
                )
            }

        // Calculate cost by agent
        val byAgent = usageRecords
            .groupBy { it.agentName }
            .mapValues { (agentName, records) ->
                val promptTokens = records.sumOf { it.promptTokens }
                val outputTokens = records.sumOf { it.outputTokens }
                val cost = records.sumOf { calculateTokenCost(it) }
                AgentCost(
                    agentName = agentName,
                    promptTokens = promptTokens,
                    outputTokens = outputTokens,
                    costUsd = cost,
                    callCount = records.size
                )
            }

        val totalCost = usageRecords.sumOf { calculateTokenCost(it) }

        return LlmCostBreakdown(
            totalPromptTokens = totalPromptTokens,
            totalOutputTokens = totalOutputTokens,
            totalCostUsd = totalCost,
            byModel = byModel,
            byAgent = byAgent
        )
    }

    private suspend fun calculateExternalApiCosts(sessionId: SessionId): ExternalApiCostBreakdown {
        val summary = externalApiUsageRepository.getSummaryBySessionId(sessionId)

        return ExternalApiCostBreakdown(
            totalCalls = summary.totalCalls,
            totalCostUsd = summary.totalCostUsd,
            byApi = summary.byApi.mapValues { (_, stats) ->
                ApiCost(
                    apiName = stats.apiName,
                    callCount = stats.callCount,
                    costUsd = stats.totalCostUsd
                )
            }
        )
    }
}
