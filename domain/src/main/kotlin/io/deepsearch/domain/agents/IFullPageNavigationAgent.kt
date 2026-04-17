package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class ExtractedContent(
    val description: String,
    val text: String,
    val isTable: Boolean = false
)

data class FullPageNavigationInput(
    val fullPageScreenshot: IBrowserPage.Screenshot,
    val query: String,
    val previousActions: List<ActionWithOutcome>,
    val questions: List<TrackedQuestion> = emptyList(),
    val generalFindings: List<String> = emptyList(),
    val pageUrl: String,
    val pageTitle: String,
    val pageDescription: String?,
    val currentIteration: Int = 1,
    val maxIterations: Int = 12,
    val pageState: List<String> = emptyList(),
    val isOverlayMode: Boolean = false,
    val labeledElements: String? = null,
    val scrollStateHint: String? = null,
    val extractedRegionContent: List<ExtractedContent> = emptyList()
) : IAgent.IAgentInput

data class FullPageNavigationOutput(
    val actions: List<NavigationAction>,
    val questionsState: List<TrackedQuestion>,
    val generalFindings: List<String>,
    val pageState: List<String>,
    val observation: String?,
    val captureRegions: List<CaptureRegion>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IFullPageNavigationAgent : IAgent<FullPageNavigationInput, FullPageNavigationOutput> {
    override suspend fun generate(input: FullPageNavigationInput): FullPageNavigationOutput
}
