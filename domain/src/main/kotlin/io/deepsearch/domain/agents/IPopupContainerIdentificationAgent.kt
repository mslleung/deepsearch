package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.serialization.Serializable

data class PopupContainerIdentificationInput(
    val screenshotBytes: ByteArray,
    val mimetype: ImageMimeType,
    val html: String
) : IAgent.IAgentInput

@Serializable
data class PopupContainerIdentificationOutput(
    val popupContainerXPaths: List<String>
) : IAgent.IAgentOutput

interface IPopupContainerIdentificationAgent : IAgent<PopupContainerIdentificationInput, PopupContainerIdentificationOutput> {
    override suspend fun generate(input: PopupContainerIdentificationInput): PopupContainerIdentificationOutput
}

