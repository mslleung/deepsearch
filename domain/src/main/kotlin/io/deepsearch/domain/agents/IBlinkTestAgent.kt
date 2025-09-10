package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery

data class BlinkTestInput(
    val searchQuery: SearchQuery,
    val screenshotBytes: ByteArray
) : IAgent.IAgentInput

data class BlinkTestOutput(
    val decision: IBlinkTestAgent.Decision,
    val rationale: String
) : IAgent.IAgentOutput

interface IBlinkTestAgent :
    IAgent<BlinkTestInput, BlinkTestOutput> {

    enum class Decision { IRRELEVANT, RELEVANT }

    override suspend fun generate(input: BlinkTestInput): BlinkTestOutput
}


