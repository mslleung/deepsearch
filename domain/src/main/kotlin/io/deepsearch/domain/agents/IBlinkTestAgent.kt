package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery

interface IBlinkTestAgent :
    IAgent<IBlinkTestAgent.BlinkTestInput, IBlinkTestAgent.BlinkTestOutput> {

    enum class Decision { IRRELEVANT, RELEVANT }

    data class BlinkTestInput(
        val searchQuery: SearchQuery,
        val screenshotBytes: ByteArray
    ) : IAgent.IAgentInput

    data class BlinkTestOutput(
        val decision: Decision,
        val rationale: String
    ) : IAgent.IAgentOutput

    override suspend fun generate(input: BlinkTestInput): BlinkTestOutput
}


