package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.serialization.Serializable

data class PopupIdentificationInput(
    val screenshotBytes: ByteArray,
    val mimetype: ImageMimeType
) : IAgent.IAgentInput

@Serializable
data class PopupIdentificationResult(
    val exists: Boolean,
    val dismissSelector: String? = null
)

data class PopupIdentificationOutput(
    val result: PopupIdentificationResult
) : IAgent.IAgentOutput

interface IPopupIdentificationAgent : IAgent<PopupIdentificationInput, PopupIdentificationOutput> {
    override suspend fun generate(input: PopupIdentificationInput): PopupIdentificationOutput
}


