package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType

data class ImageTextExtractionInput(
    val bytes: ByteArray,
    val mimeType: ImageMimeType,
    val context: Map<String, String> = emptyMap()
) : IAgent.IAgentInput

data class ImageTextExtractionOutput(
    val extractedText: String?
) : IAgent.IAgentOutput

interface IImageTextExtractionAgent : IAgent<ImageTextExtractionInput, ImageTextExtractionOutput> {
    override suspend fun generate(input: ImageTextExtractionInput): ImageTextExtractionOutput
}
