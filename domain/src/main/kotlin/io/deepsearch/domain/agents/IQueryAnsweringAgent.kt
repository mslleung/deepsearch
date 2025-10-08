package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery

data class QueryAnsweringInput(
    val screenshotBytes: ByteArray,
    val html: String,
    val searchQuery: SearchQuery
) : IAgent.IAgentInput

data class QueryAnsweringOutput(
    val answer: String
) : IAgent.IAgentOutput

interface IQueryAnsweringAgent : IAgent<QueryAnsweringInput, QueryAnsweringOutput> {
    override suspend fun generate(input: QueryAnsweringInput): QueryAnsweringOutput
}
