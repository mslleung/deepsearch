package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult

data class GoogleTextSearchInput(val searchQuery: SearchQuery) : IAgent.IAgentInput

data class GoogleTextSearchOutput(val searchResult: SearchResult) : IAgent.IAgentOutput

interface IGoogleTextSearchAgent :
    IAgent<GoogleTextSearchInput, GoogleTextSearchOutput> {

    override suspend fun generate(input: GoogleTextSearchInput): GoogleTextSearchOutput
}


