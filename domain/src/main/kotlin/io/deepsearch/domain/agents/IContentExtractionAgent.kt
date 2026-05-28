package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class ContentExtractionInput(
    val cleanScreenshot: IBrowserPage.Screenshot,
    val query: String,
    val extractedRegionContent: List<ExtractedContent> = emptyList(),
    val currentIteration: Int = 1
) : IAgent.IAgentInput

data class ContentExtractionOutput(
    val captureRegions: List<CaptureRegion>,
    val observation: String?,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IContentExtractionAgent : IAgent<ContentExtractionInput, ContentExtractionOutput> {
    override suspend fun generate(input: ContentExtractionInput): ContentExtractionOutput
}
