package io.deepsearch.domain.agents

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

/**
 * Stateful agent that wraps Gemini's Computer Use tool for browser-based navigation.
 *
 * Unlike [IFullPageNavigationAgent] which is stateless per call, this agent maintains
 * a multi-turn conversation history across iterations. It sends viewport screenshots
 * to the model, receives UI actions (click, type, scroll), and continues until the
 * model produces a text answer.
 *
 * The agent does NOT execute actions itself -- it only decides what to do.
 * Action execution is handled by the calling service.
 */
interface IComputerUseNavigationAgent {

    suspend fun startSession(
        query: String,
        screenshot: IBrowserPage.Screenshot,
        currentUrl: String
    ): ComputerUseResponse

    suspend fun continueSession(
        functionResults: List<CuFunctionResult>
    ): ComputerUseResponse

    fun resetSession()
}

sealed class ComputerUseResponse {
    data class Actions(
        val functionCalls: List<CuFunctionCall>,
        val tokenUsage: TokenUsageMetrics
    ) : ComputerUseResponse()

    data class Answer(
        val text: String,
        val tokenUsage: TokenUsageMetrics
    ) : ComputerUseResponse()
}

data class CuFunctionCall(
    val id: String,
    val name: String,
    val args: Map<String, Any>,
    val intent: String?
)

data class CuFunctionResult(
    val callId: String,
    val name: String,
    val screenshot: IBrowserPage.Screenshot,
    val currentUrl: String,
    val error: String? = null
)
