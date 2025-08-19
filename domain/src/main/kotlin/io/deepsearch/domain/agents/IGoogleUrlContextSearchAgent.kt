package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchResult

interface IGoogleUrlContextSearchAgent :
    IAgent<IGoogleUrlContextSearchAgent.GoogleUrlContextSearchInput, IGoogleUrlContextSearchAgent.GoogleUrlContextSearchOutput> {

    data class GoogleUrlContextSearchInput(
        val query: String,
        val urls: List<String>
    ) : IAgent.IAgentInput

    data class GoogleUrlContextSearchOutput(val content: String, val sources: List<String>) : IAgent.IAgentOutput

    override suspend fun generate(input: GoogleUrlContextSearchInput): GoogleUrlContextSearchOutput
}

