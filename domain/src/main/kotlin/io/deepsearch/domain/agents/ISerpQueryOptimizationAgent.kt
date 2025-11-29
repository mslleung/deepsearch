package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class SerpQueryOptimizationInput(
    val query: String,
    val targetUrl: String
) : IAgent.IAgentInput

data class SerpQueryOptimizationOutput(
    val optimizedQuery: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface ISerpQueryOptimizationAgent :
    IAgent<SerpQueryOptimizationInput, SerpQueryOptimizationOutput> {

    override suspend fun generate(input: SerpQueryOptimizationInput): SerpQueryOptimizationOutput
}

