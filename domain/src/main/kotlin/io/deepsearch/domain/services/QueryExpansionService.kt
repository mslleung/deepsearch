package io.deepsearch.domain.services

import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery

interface IQueryExpansionService {
    suspend fun expandQuery(searchQuery: SearchQuery): List<SearchQuery>
}

class QueryExpansionService(private val queryExpansionAgent: IQueryExpansionAgent) : IQueryExpansionService {

    override suspend fun expandQuery(searchQuery: SearchQuery): List<SearchQuery> {
        val agentInput = IQueryExpansionAgent.QueryExpansionAgentInput(searchQuery = searchQuery)

        val agentOutput = queryExpansionAgent.generate(agentInput)

        return agentOutput.expandedQueries
    }

}