package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.serialization.Serializable

data class PopupIdentificationInput(
    val screenshotBytes: ByteArray,
    val mimetype: ImageMimeType,
    val html: String
) : IAgent.IAgentInput

@Serializable
data class PopupIdentificationOutput(
    val dismissButtonXPath: String?
) : IAgent.IAgentOutput

interface IPopupIdentificationAgent : IAgent<PopupIdentificationInput, PopupIdentificationOutput> {
    override suspend fun generate(input: PopupIdentificationInput): PopupIdentificationOutput
}


