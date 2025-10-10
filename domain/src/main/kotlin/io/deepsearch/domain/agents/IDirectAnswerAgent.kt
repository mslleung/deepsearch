package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery

data class DirectAnswerInput(
    val screenshotBytes: ByteArray,
    val html: String,
    val searchQuery: SearchQuery
) : IAgent.IAgentInput

data class DirectAnswerOutput(
    val answer: String
) : IAgent.IAgentOutput

interface IDirectAnswerAgent : IAgent<DirectAnswerInput, DirectAnswerOutput> {
    override suspend fun generate(input: DirectAnswerInput): DirectAnswerOutput
}
