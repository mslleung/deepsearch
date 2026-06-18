package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class IdentifiedTableSubRegion(
    val role: TableRegionRole,
    val description: String,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int
)

data class IdentifiedRegion(
    val description: String,
    val relevance: String,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val containsTable: Boolean = false,
    val tableSubRegions: List<IdentifiedTableSubRegion> = emptyList()
)

data class VisualSegmentationInput(
    val screenshot: IBrowserPage.Screenshot,
    val query: String,
    val extractedRegionContent: List<ExtractedContent> = emptyList(),
    val regionDescriptions: List<RegionDescription> = emptyList()
) : IAgent.IAgentInput

data class VisualSegmentationOutput(
    val regions: List<IdentifiedRegion>,
    val observation: String?,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IVisualSegmentationAgent : IAgent<VisualSegmentationInput, VisualSegmentationOutput> {
    override suspend fun generate(input: VisualSegmentationInput): VisualSegmentationOutput
}
