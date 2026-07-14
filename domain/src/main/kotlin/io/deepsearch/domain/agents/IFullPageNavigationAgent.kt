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

data class ExplorationDirection(
    val direction: String,
    val status: String
)

@Serializable
sealed class NavigationAction {
    @Serializable
    data class Click(
        val elementLabel: Int,
        val reason: String,
        val target: String? = null,
        val resolvedCenterX: Int? = null,
        val resolvedCenterY: Int? = null
    ) : NavigationAction()

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

    @Serializable
    data object GiveUp : NavigationAction()
}

@Serializable
enum class ScrollDirection {
    DOWN, UP, LEFT, RIGHT
}

data class BoundingBox(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int
)

enum class TableRegionRole { HEADER, DATA, CONTEXT }

data class TableSubRegion(
    val boundingBox: BoundingBox,
    val role: TableRegionRole,
    val description: String
)

sealed class CaptureRegion {
    abstract val relevance: String

    data class Element(
        override val relevance: String,
        val boundingBox: BoundingBox
    ) : CaptureRegion()

    data class Table(
        override val relevance: String,
        val regions: List<TableSubRegion>
    ) : CaptureRegion()
}

data class ExtractedContent(
    val description: String,
    val text: String,
    val isTable: Boolean = false
)

enum class NavigationMode {
    FULL_PAGE,
    VIEWPORT
}

data class FullPageNavigationInput(
    val fullPageScreenshot: IBrowserPage.Screenshot,
    val query: String,
    val previousActions: List<ActionWithOutcome>,
    val pageUrl: String,
    val pageTitle: String,
    val pageState: List<String> = emptyList(),
    val navigationMode: NavigationMode = NavigationMode.FULL_PAGE,
    val explorationDirections: List<ExplorationDirection> = emptyList(),
    val extractedContent: List<ExtractedContent> = emptyList(),
    val currentIteration: Int = 1,
    val maxIterations: Int = 12
) : IAgent.IAgentInput

data class FullPageNavigationOutput(
    val actions: List<NavigationAction>,
    val pageState: List<String>,
    val observation: String?,
    val explorationDirections: List<ExplorationDirection>,
    val currentDirection: String?,
    val searchComplete: Boolean,
    val allDirectionsExhausted: Boolean,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IFullPageNavigationAgent : IAgent<FullPageNavigationInput, FullPageNavigationOutput> {
    override suspend fun generate(input: FullPageNavigationInput): FullPageNavigationOutput
}
