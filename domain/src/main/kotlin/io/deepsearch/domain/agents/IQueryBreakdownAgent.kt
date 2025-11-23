package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class QueryBreakdownAgentInput(
    val searchQuery: SearchQuery
) : IAgent.IAgentInput
data class QueryBreakdownAgentOutput(
    val breakdownPoints: List<String>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IQueryBreakdownAgent :
    IAgent<QueryBreakdownAgentInput, QueryBreakdownAgentOutput> {

    override suspend fun generate(input: QueryBreakdownAgentInput): QueryBreakdownAgentOutput
}

