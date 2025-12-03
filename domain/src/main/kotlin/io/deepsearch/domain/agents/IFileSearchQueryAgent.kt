package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.FileSearchChunk
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class FileSearchQueryInput(
    val storeName: String,
    val query: String,
    val maxAgeMs: Long? = null
) : IAgent.IAgentInput

data class FileSearchQueryOutput(
    val chunks: List<FileSearchChunk>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IFileSearchQueryAgent :
    IAgent<FileSearchQueryInput, FileSearchQueryOutput> {

    override suspend fun generate(input: FileSearchQueryInput): FileSearchQueryOutput
}

