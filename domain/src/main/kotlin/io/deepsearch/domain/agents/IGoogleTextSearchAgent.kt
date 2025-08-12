package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult

interface IGoogleTextSearchAgent :
    IAgent<IGoogleTextSearchAgent.GoogleTextSearchInput, IGoogleTextSearchAgent.GoogleTextSearchOutput> {

    data class GoogleTextSearchInput(val searchQuery: SearchQuery) : IAgent.IAgentInput

    data class GoogleTextSearchOutput(val searchResult: SearchResult) : IAgent.IAgentOutput

    override suspend fun generate(input: GoogleTextSearchInput): GoogleTextSearchOutput
}


