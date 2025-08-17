package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult

interface IVisualAnalysisAgent :
    IAgent<IVisualAnalysisAgent.VisualAnalysisInput, IVisualAnalysisAgent.VisualAnalysisOutput> {

    data class VisualAnalysisInput(
        val searchQuery: SearchQuery,
        val screenshotBytes: ByteArray
    ) : IAgent.IAgentInput

    data class VisualAnalysisOutput(
        val searchResult: SearchResult
    ) : IAgent.IAgentOutput

    override suspend fun generate(input: VisualAnalysisInput): VisualAnalysisOutput
}


