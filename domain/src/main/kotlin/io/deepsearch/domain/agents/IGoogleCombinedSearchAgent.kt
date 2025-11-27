package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class GoogleCombinedSearchInput(
    val searchQuery: SearchQuery
) : IAgent.IAgentInput

data class GoogleCombinedSearchOutput(
    val answer: String,
    val answerSources: List<String>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IGoogleCombinedSearchAgent :
    IAgent<GoogleCombinedSearchInput, GoogleCombinedSearchOutput> {

    override suspend fun generate(input: GoogleCombinedSearchInput): GoogleCombinedSearchOutput
}


