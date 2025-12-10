package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

data class SemanticIdentificationInput(
    val webpage: IBrowserPage,
    /** Pre-captured page snapshot containing HTML and bounding boxes (without media). */
    val pageSnapshot: IBrowserPage.PageSnapshotWithMetadata
) : IAgent.IAgentInput

@Serializable
data class SemanticIdentificationOutput(
    val elements: SemanticElements,
    @Contextual val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface ISemanticIdentificationAgent : IAgent<SemanticIdentificationInput, SemanticIdentificationOutput> {
    override suspend fun generate(input: SemanticIdentificationInput): SemanticIdentificationOutput
}

