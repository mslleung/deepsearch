package io.deepsearch.domain.services

import io.deepsearch.domain.agents.IQueryExpansionAgent

interface IQueryExpansionService {
    suspend fun expandQuery(query: String): List<String>
}

class QueryExpansionService(private val queryExpansionAgent: IQueryExpansionAgent) : IQueryExpansionService {

    override suspend fun expandQuery(query: String): List<String> {
        val agentInput = IQueryExpansionAgent.QueryExpansionAgentInput(query = query)

        val agentOutput = queryExpansionAgent.generate(agentInput)

        return agentOutput.expandedQueries
    }

}