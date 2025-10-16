package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent

data class PdfToMarkdownInput(
    val pdfBytes: ByteArray
) : IAgent.IAgentInput

data class PdfToMarkdownOutput(
    val markdown: String
) : IAgent.IAgentOutput

interface IPdfToMarkdownAgent : IAgent<PdfToMarkdownInput, PdfToMarkdownOutput> {
    override suspend fun generate(input: PdfToMarkdownInput): PdfToMarkdownOutput
}

