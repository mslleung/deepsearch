package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Serializable

data class ActionWithOutcome(
    val action: NavigationAction,
    val outcome: String? = null,
    val observation: String? = null
)

data class TrackedQuestion(
    val question: String,
    val resolved: Boolean
)

@Serializable
sealed class NavigationAction {
    @Serializable
    data class Click(
        val elementLabel: Int? = null,
        val box2d: List<Int>? = null,
        val label: String? = null,
        val reason: String
    ) : NavigationAction() {
        val centerX: Int? get() = box2d?.let { (it[1] + it[3]) / 2 }
        val centerY: Int? get() = box2d?.let { (it[0] + it[2]) / 2 }
    }

    @Serializable
    data class ExplorationFinished(
        val answer: String?,
        val contentDate: String? = null
    ) : NavigationAction()

    @Serializable
    data class ScrollAt(
        val x: Int,
        val y: Int,
        val scrollDirection: ScrollDirection,
        val scrollPercent: Int = 100,
        val reason: String
    ) : NavigationAction()

    @Serializable
    data class Type(
        val x: Int,
        val y: Int,
        val text: String,
        val reason: String
    ) : NavigationAction()
}

@Serializable
enum class ScrollDirection {
    DOWN, UP, LEFT, RIGHT
}

data class CaptureRegion(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val relevance: String,
    val containsTable: Boolean = false
)

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
    val pageUrl: String,
    val pageTitle: String,
    val currentIteration: Int = 1,
    val maxIterations: Int = 12,
    val pageState: List<String> = emptyList(),
    val isOverlayMode: Boolean = false,
    val scrollStateHint: String? = null,
    val extractedRegionContent: List<ExtractedContent> = emptyList()
) : IAgent.IAgentInput

data class FullPageNavigationOutput(
    val actions: List<NavigationAction>,
    val questions: List<TrackedQuestion>,
    val pageState: List<String>,
    val observation: String?,
    val captureRegions: List<CaptureRegion>,
    val decision: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IFullPageNavigationAgent : IAgent<FullPageNavigationInput, FullPageNavigationOutput> {
    override suspend fun generate(input: FullPageNavigationInput): FullPageNavigationOutput
}
