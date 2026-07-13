package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class LocatorTarget(
    val index: Int,
    val target: String,
    val reason: String
)

data class ElementLocatorInput(
    val screenshot: IBrowserPage.Screenshot,
    val targets: List<LocatorTarget>,
    val pageUrl: String,
    val elementIndex: Map<Int, ElementLocatorLabel> = emptyMap()
) : IAgent.IAgentInput

data class ElementLocatorLabel(
    val tag: String,
    val text: String,
    val role: String?,
    val ariaLabel: String?,
    val centerX: Int,
    val centerY: Int
)

data class ResolvedTarget(
    val index: Int,
    val elementLabel: Int? = null,
    val confidence: String?,
    val centerXNorm: Int? = null,
    val centerYNorm: Int? = null
)

data class ElementLocatorOutput(
    val resolved: List<ResolvedTarget>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IElementLocatorAgent : IAgent<ElementLocatorInput, ElementLocatorOutput> {
    override suspend fun generate(input: ElementLocatorInput): ElementLocatorOutput
}
