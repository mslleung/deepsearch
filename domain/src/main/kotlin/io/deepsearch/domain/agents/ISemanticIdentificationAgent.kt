package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SemanticElementType
import kotlinx.serialization.Serializable

data class SemanticIdentificationInput(
    val screenshotBytes: ByteArray,
    val mimetype: ImageMimeType,
    val html: String
) : IAgent.IAgentInput

@Serializable
data class IdentifiedSemanticElement(
    val xpath: String,
    val type: SemanticElementType,
    val note: String
)

@Serializable
data class SemanticIdentificationOutput(
    val elements: List<IdentifiedSemanticElement>
) : IAgent.IAgentOutput

interface ISemanticIdentificationAgent : IAgent<SemanticIdentificationInput, SemanticIdentificationOutput> {
    override suspend fun generate(input: SemanticIdentificationInput): SemanticIdentificationOutput
}

