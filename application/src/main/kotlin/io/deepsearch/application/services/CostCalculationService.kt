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
     * @throws IllegalArgumentException if model pricing not found
     */
    fun getModelPricing(modelName: String): Pair<Double, Double>
}

/**
 * Service for calculating search session costs based on LLM token usage and external API calls.
 * 
 * Pricing sources:
 * - Gemini API: https://ai.google.dev/gemini-api/docs/pricing
 * - Serper API: https://serper.dev/ ($1/1k queries)
 */
class CostCalculationService(
    private val llmTokenUsageRepository: ILlmTokenUsageRepository,
    private val externalApiUsageRepository: IExternalApiUsageRepository
) : ICostCalculationService {

    companion object {
        /**
         * Gemini pricing per 1M tokens (input, output) in USD.
         * Updated: January 2026
         * Source: https://ai.google.dev/gemini-api/docs/pricing
         */
        private val GEMINI_PRICING = mapOf(
            // Pro models
            ModelIds.GEMINI_2_5_PRO.modelId to Pair(1.25, 10.00),
            ModelIds.GEMINI_3_PRO_PREVIEW.modelId to Pair(1.25, 10.00),

            // Flash models
            ModelIds.GEMINI_2_5_FLASH.modelId to Pair(0.15, 0.60),
            ModelIds.GEMINI_2_5_FLASH_PREVIEW.modelId to Pair(0.15, 0.60),
            ModelIds.GEMINI_2_0_FLASH.modelId to Pair(0.10, 0.40),

            // Flash Lite models
            ModelIds.GEMINI_2_5_FLASH_LITE.modelId to Pair(0.10, 0.40),
            ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId to Pair(0.10, 0.40),
            ModelIds.GEMINI_2_0_FLASH_LITE.modelId to Pair(0.075, 0.30),

            // Embedding models (free for text input)
            ModelIds.GEMINI_EMBEDDING_001.modelId to Pair(0.0, 0.0),
        )
    }

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
        return GEMINI_PRICING[modelName] 
            ?: throw IllegalArgumentException("Pricing not found for model: $modelName")
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
