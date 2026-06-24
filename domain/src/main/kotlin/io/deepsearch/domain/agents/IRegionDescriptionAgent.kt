package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class TableSubRegionDescription(
    val description: String,
    val role: String,
    val visualLocation: String
)

data class RegionDescription(
    val description: String,
    val relevance: String,
    val visualLocation: String,
    val roughYMin: Int? = null,
    val roughYMax: Int? = null,
    val containsTable: Boolean = false,
    val tableSubRegions: List<TableSubRegionDescription> = emptyList()
)

data class RegionDescriptionInput(
    val screenshot: IBrowserPage.Screenshot,
    val query: String,
    val extractedContent: List<ExtractedContent> = emptyList()
) : IAgent.IAgentInput

data class RegionDescriptionOutput(
    val descriptions: List<RegionDescription>,
    val observation: String?,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IRegionDescriptionAgent : IAgent<RegionDescriptionInput, RegionDescriptionOutput> {
    override suspend fun generate(input: RegionDescriptionInput): RegionDescriptionOutput
}
