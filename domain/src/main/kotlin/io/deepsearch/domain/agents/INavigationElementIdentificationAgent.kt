package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.NavigationElementType
import kotlinx.serialization.Serializable

data class NavigationElementIdentificationInput(
    val html: String
) : IAgent.IAgentInput

@Serializable
data class IdentifiedNavigationElement(
    val xpath: String,
    val type: NavigationElementType,
    val note: String
)

@Serializable
data class NavigationElementIdentificationOutput(
    val elements: List<IdentifiedNavigationElement>
) : IAgent.IAgentOutput

interface INavigationElementIdentificationAgent : IAgent<NavigationElementIdentificationInput, NavigationElementIdentificationOutput> {
    override suspend fun generate(input: NavigationElementIdentificationInput): NavigationElementIdentificationOutput
}

