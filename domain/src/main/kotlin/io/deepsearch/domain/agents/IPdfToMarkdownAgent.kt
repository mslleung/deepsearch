package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class PdfToMarkdownInput(
    val pdfBytes: ByteArray
) : IAgent.IAgentInput

data class PdfToMarkdownOutput(
    val markdown: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IPdfToMarkdownAgent : IAgent<PdfToMarkdownInput, PdfToMarkdownOutput> {
    override suspend fun generate(input: PdfToMarkdownInput): PdfToMarkdownOutput
}

