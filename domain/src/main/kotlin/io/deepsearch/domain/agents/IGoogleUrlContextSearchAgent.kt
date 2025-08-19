package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult

interface IGoogleUrlContextSearchAgent :
    IAgent<IGoogleUrlContextSearchAgent.GoogleUrlContextSearchInput, IGoogleUrlContextSearchAgent.GoogleUrlContextSearchOutput> {

    data class GoogleUrlContextSearchInput(val searchQuery: SearchQuery) : IAgent.IAgentInput

    data class GoogleUrlContextSearchOutput(val searchResult: SearchResult) : IAgent.IAgentOutput

    override suspend fun generate(input: GoogleUrlContextSearchInput): GoogleUrlContextSearchOutput
}

