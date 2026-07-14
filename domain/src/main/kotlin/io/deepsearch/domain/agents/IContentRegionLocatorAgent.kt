package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class ContentRegionLocatorInput(
    val screenshot: IBrowserPage.Screenshot,
    val query: String,
    val extractedContent: List<ExtractedContent> = emptyList()
) : IAgent.IAgentInput

data class ContentRegionLocatorOutput(
    val regions: List<IdentifiedRegion>,
    val observation: String?,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IContentRegionLocatorAgent : IAgent<ContentRegionLocatorInput, ContentRegionLocatorOutput> {
    override suspend fun generate(input: ContentRegionLocatorInput): ContentRegionLocatorOutput
}
