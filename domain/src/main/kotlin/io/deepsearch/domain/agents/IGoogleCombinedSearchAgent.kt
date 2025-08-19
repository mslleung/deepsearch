package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult

interface IGoogleCombinedSearchAgent :
    IAgent<IGoogleCombinedSearchAgent.GoogleCombinedSearchInput, IGoogleCombinedSearchAgent.GoogleCombinedSearchOutput> {

    data class GoogleCombinedSearchInput(val searchQuery: SearchQuery) : IAgent.IAgentInput

    data class GoogleCombinedSearchOutput(val searchResult: SearchResult) : IAgent.IAgentOutput

    override suspend fun generate(input: GoogleCombinedSearchInput): GoogleCombinedSearchOutput
}


