package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Serializable

data class WebpageNavigationInput(
    val screenshot: IBrowserPage.Screenshot,
    val query: String,
    val pageContext: String,
    val previousActions: List<NavigationAction>,
    val previousActionOutcome: String?,
    val elementLabels: List<ElementLabel>,
    val answeredQuestions: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList()
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
    data class Scroll(val direction: ScrollDirection) : NavigationAction()

    @Serializable
    data class AnswerFound(
        val answer: String,
        val evidence: String,
        val intention: String,
        val contentDate: String?
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
}

@Serializable
enum class ScrollDirection {
    DOWN, UP
}

/**
 * Every VLM response includes [finding] (what's relevant on the current screenshot)
 * and [openQuestions] (gaps that remain), alongside the page [action].
 * This ensures the agent observes AND acts in every iteration — no wasted turns.
 */
data class WebpageNavigationOutput(
    val action: NavigationAction,
    val finding: String?,
    val openQuestions: List<String>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IWebpageNavigationAgent : IAgent<WebpageNavigationInput, WebpageNavigationOutput> {
    override suspend fun generate(input: WebpageNavigationInput): WebpageNavigationOutput
}
