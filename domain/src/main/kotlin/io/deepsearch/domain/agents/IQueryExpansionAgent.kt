package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery

interface IQueryExpansionAgent :
    IAgent<IQueryExpansionAgent.QueryExpansionAgentInput, IQueryExpansionAgent.QueryExpansionAgentOutput> {

    data class QueryExpansionAgentInput(val searchQuery: SearchQuery) : IAgent.IAgentInput
    data class QueryExpansionAgentOutput(val expandedQueries: List<SearchQuery>) : IAgent.IAgentOutput

    override suspend fun generate(input: QueryExpansionAgentInput): QueryExpansionAgentOutput
}