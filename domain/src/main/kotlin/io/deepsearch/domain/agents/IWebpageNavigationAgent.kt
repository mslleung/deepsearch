package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Serializable

data class ActionWithOutcome(
    val action: NavigationAction,
    val outcome: String? = null,
    val observation: String? = null,
    val findings: List<String> = emptyList()
)

data class TrackedQuestion(
    val question: String,
    val resolved: Boolean,
    val findings: List<String> = emptyList()
)

data class WebpageNavigationInput(
    val screenshot: IBrowserPage.Screenshot,
    val query: String,
    val previousActions: List<ActionWithOutcome>,
    val questions: List<TrackedQuestion> = emptyList(),
    val generalFindings: List<String> = emptyList(),
    val pageUrl: String,
    val pageTitle: String,
    val pageDescription: String?,
    val scrollPercent: Int,
    val currentIteration: Int = 1,
    val maxIterations: Int = 12
) : IAgent.IAgentInput

@Serializable
sealed class NavigationAction {
    @Serializable
    data class Click(
        val x: Int,
        val y: Int,
        val reason: String
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
    data class PeekFullPage(
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
    val relevance: String
)

/**
 * The model outputs the full question-findings map each turn via [questionsState].
 * The service replaces its state with this output (no append/dedup needed).
 *
 * [observation] is fed back in the turn history so the agent maintains reasoning
 * continuity across turns. [questionsState] tracks each question (open or resolved)
 * alongside the findings that answer it. [generalFindings] captures facts not tied
 * to any specific question.
 *
 * [captureRegions] allows the agent to flag visual regions (charts, diagrams, tables, etc.)
 * worth capturing as images, using 0-1000 normalized bounding boxes.
 */
data class WebpageNavigationOutput(
    val actions: List<NavigationAction>,
    val questionsState: List<TrackedQuestion>,
    val generalFindings: List<String>,
    val observation: String?,
    val captureRegions: List<CaptureRegion>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

data class SearchKeywordsResult(
    val keywords: List<String>,
    val tokenUsage: TokenUsageMetrics
)

interface IWebpageNavigationAgent : IAgent<WebpageNavigationInput, WebpageNavigationOutput> {
    override suspend fun generate(input: WebpageNavigationInput): WebpageNavigationOutput
    suspend fun generateSearchKeywords(query: String): SearchKeywordsResult
}
