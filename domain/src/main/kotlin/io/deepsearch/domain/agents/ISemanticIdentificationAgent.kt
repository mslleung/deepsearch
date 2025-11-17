package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SemanticElements
import kotlinx.serialization.Serializable

data class SemanticIdentificationInput(
    val html: String
) : IAgent.IAgentInput

@Serializable
data class SemanticIdentificationOutput(
    val elements: SemanticElements
) : IAgent.IAgentOutput

interface ISemanticIdentificationAgent : IAgent<SemanticIdentificationInput, SemanticIdentificationOutput> {
    override suspend fun generate(input: SemanticIdentificationInput): SemanticIdentificationOutput
}

