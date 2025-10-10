package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent

data class MarkdownConversionInput(
    val screenshotBytes: ByteArray,
    val html: String
) : IAgent.IAgentInput

data class MarkdownConversionOutput(
    val markdown: String
) : IAgent.IAgentOutput

interface IMarkdownConversionAgent : IAgent<MarkdownConversionInput, MarkdownConversionOutput> {
    override suspend fun generate(input: MarkdownConversionInput): MarkdownConversionOutput
}
