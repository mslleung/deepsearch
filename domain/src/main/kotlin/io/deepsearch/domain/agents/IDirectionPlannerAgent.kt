package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class DirectionPlannerInput(
    val screenshot: IBrowserPage.Screenshot,
    val query: String,
    val interactiveElementsHint: String?,
    val previousActions: List<ActionWithOutcome>,
    val explorationDirections: List<ExplorationDirection>,
    val extractedContent: List<ExtractedContent>,
    val currentIteration: Int,
    val maxIterations: Int
) : IAgent.IAgentInput

data class DirectionPlannerOutput(
    val explorationDirections: List<ExplorationDirection>,
    val nextDirectionHint: String?,
    val allDirectionsExhausted: Boolean,
    val searchComplete: Boolean,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IDirectionPlannerAgent : IAgent<DirectionPlannerInput, DirectionPlannerOutput>
