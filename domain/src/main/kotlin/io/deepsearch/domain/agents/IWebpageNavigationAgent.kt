package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Serializable

data class ActionWithOutcome(
    val action: NavigationAction,
    val outcome: String? = null
)

data class WebpageNavigationInput(
    val screenshot: IBrowserPage.Screenshot,
    val query: String,
    val previousActions: List<ActionWithOutcome>,
    val elementLabels: List<ElementLabel>,
    val answeredQuestions: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val pageUrl: String,
    val pageTitle: String,
    val pageDescription: String?,
    val scrollPercent: Int
) : IAgent.IAgentInput

/**
 * Compact description of a labeled interactive element, sent alongside the
 * annotated screenshot so the VLM has both visual and textual reference.
 */
data class ElementLabel(
    val labelNumber: Int,
    val tag: String,
    val text: String,
    val role: String?,
    val states: List<String> = emptyList()
)

@Serializable
sealed class NavigationAction {
    @Serializable
    data class Click(
        val labelNumber: Int,
        val reason: String,
        val elementDescription: String? = null
    ) : NavigationAction()

    @Serializable
    data class Scroll(
        val scrollDirection: ScrollDirection,
        val scrollPercent: Int = 100,
        val reason: String
    ) : NavigationAction()

    @Serializable
    data class FindOnPage(
        val keywords: List<String>,
        val reason: String
    ) : NavigationAction()

    @Serializable
    data class ScrollToText(
        val searchText: String,
        val occurrence: Int = 1,
        val reason: String
    ) : NavigationAction()

    @Serializable
    data class AnswerFound(
        val answer: String,
        val contentDate: String? = null
    ) : NavigationAction()

    @Serializable
    data class ClickAt(
        val x: Int,
        val y: Int,
        val reason: String,
        val elementDescription: String? = null
    ) : NavigationAction()

    @Serializable
    data class GiveUp(val reason: String) : NavigationAction()

    @Serializable
    data class PeekFullPage(
        val reason: String
    ) : NavigationAction()

    @Serializable
    data class Type(
        val labelNumber: Int,
        val text: String,
        val reason: String,
        val elementDescription: String? = null
    ) : NavigationAction()
}

@Serializable
enum class ScrollDirection {
    DOWN, UP
}

data class CaptureRegion(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val relevance: String
)

/**
 * Every VLM response includes [finding] (what's relevant on the current screenshot)
 * and [openQuestions] (gaps that remain), alongside the page [action].
 * This ensures the agent observes AND acts in every iteration — no wasted turns.
 *
 * [captureRegions] allows the agent to flag visual regions (charts, diagrams, tables, etc.)
 * worth capturing as images, using 0-1000 normalized bounding boxes.
 */
data class WebpageNavigationOutput(
    val action: NavigationAction,
    val finding: String?,
    val openQuestions: List<String>,
    val captureRegions: List<CaptureRegion>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IWebpageNavigationAgent : IAgent<WebpageNavigationInput, WebpageNavigationOutput> {
    override suspend fun generate(input: WebpageNavigationInput): WebpageNavigationOutput
}
