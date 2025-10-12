package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.SemanticElements
import kotlinx.serialization.Serializable

data class NavigationElementIdentificationInput(
    val html: String
) : IAgent.IAgentInput

@Serializable
data class NavigationElementIdentificationOutput(
    val elements: SemanticElements
) : IAgent.IAgentOutput

interface INavigationElementIdentificationAgent : IAgent<NavigationElementIdentificationInput, NavigationElementIdentificationOutput> {
    override suspend fun generate(input: NavigationElementIdentificationInput): NavigationElementIdentificationOutput
}

