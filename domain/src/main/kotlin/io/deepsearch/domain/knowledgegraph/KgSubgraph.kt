package io.deepsearch.domain.knowledgegraph

import kotlinx.serialization.Serializable

/**
 * Represents a subgraph retrieved from the knowledge graph.
 * Contains entities and relationships from graph traversal.
 * 
 * @property entities List of entities in the subgraph
 * @property relationships List of relationships between entities
 * @property queryEntities Entry point entity IDs from the query
 */
@Serializable
data class KgSubgraph(
    val entities: List<KgEntity>,
    val relationships: List<KgRelationship>,
    val queryEntities: List<String> = emptyList()
) {
    fun isEmpty(): Boolean = entities.isEmpty()
    fun isNotEmpty(): Boolean = entities.isNotEmpty()
}

