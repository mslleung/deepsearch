package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class QueryExpansionAgentInput(
    val searchQuery: SearchQuery
) : IAgent.IAgentInput
data class QueryExpansionAgentOutput(
    val expandedQueries: List<SearchQuery>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IQueryExpansionAgent :
    IAgent<QueryExpansionAgentInput, QueryExpansionAgentOutput> {

    override suspend fun generate(input: QueryExpansionAgentInput): QueryExpansionAgentOutput
}