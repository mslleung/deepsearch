package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.serialization.Serializable

data class NavigationElementIdentificationInput(
    val screenshotBytes: ByteArray,
    val mimetype: ImageMimeType,
    val html: String
) : IAgent.IAgentInput

@Serializable
data class NavigationElementIdentificationOutput(
    val headerXPath: String?,
    val footerXPath: String?
) : IAgent.IAgentOutput

interface INavigationElementIdentificationAgent : IAgent<NavigationElementIdentificationInput, NavigationElementIdentificationOutput> {
    override suspend fun generate(input: NavigationElementIdentificationInput): NavigationElementIdentificationOutput
}

