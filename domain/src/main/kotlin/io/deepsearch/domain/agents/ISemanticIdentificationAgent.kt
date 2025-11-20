package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.SemanticElements
import kotlinx.serialization.Serializable

data class SemanticIdentificationInput(
    val webpage: IBrowserPage
) : IAgent.IAgentInput

@Serializable
data class SemanticIdentificationOutput(
    val elements: SemanticElements
) : IAgent.IAgentOutput

interface ISemanticIdentificationAgent : IAgent<SemanticIdentificationInput, SemanticIdentificationOutput> {
    override suspend fun generate(input: SemanticIdentificationInput): SemanticIdentificationOutput
}

