package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

data class PopupContainerIdentificationInput(
    val screenshotBytes: ByteArray,
    val mimetype: ImageMimeType,
    val html: String
) : IAgent.IAgentInput

@Serializable
data class PopupContainerIdentificationOutput(
    val popupContainerXPaths: List<String>,
    @Contextual val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

interface IPopupContainerIdentificationAgent : IAgent<PopupContainerIdentificationInput, PopupContainerIdentificationOutput> {
    override suspend fun generate(input: PopupContainerIdentificationInput): PopupContainerIdentificationOutput
}

