package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent

data class StreamingAnswerInput(
    val query: String,
    val currentAnswer: String?,
    val markdownBatch: List<String>
) : IAgent.IAgentInput

data class StreamingAnswerOutput(
    val updatedAnswer: String
) : IAgent.IAgentOutput

interface IStreamingAnswerAgent : IAgent<StreamingAnswerInput, StreamingAnswerOutput> {
    override suspend fun generate(input: StreamingAnswerInput): StreamingAnswerOutput
}
