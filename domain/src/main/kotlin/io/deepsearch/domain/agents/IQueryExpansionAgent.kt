package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent

interface IQueryExpansionAgent :
    IAgent<IQueryExpansionAgent.QueryExpansionAgentInput, IQueryExpansionAgent.QueryExpansionAgentOutput> {

    data class QueryExpansionAgentInput(val query: String) : IAgent.IAgentInput
    data class QueryExpansionAgentOutput(val expandedQueries: List<String>) : IAgent.IAgentOutput

    override suspend fun generate(input: QueryExpansionAgentInput): QueryExpansionAgentOutput
}