package io.deepsearch.domain.knowledgegraph

import kotlinx.serialization.Serializable

/**
 * Represents an entity in the knowledge graph.
 * 
 * @property id Unique identifier for the entity (UUID as string)
 * @property name The entity name as extracted from content
 * @property type Classification of the entity type
 * @property facts List of facts extracted about this entity
 * @property sourceUrls List of URLs that contributed to this entity
 */
@Serializable
data class KgEntity(
    val id: String,
    val name: String,
    val type: EntityType,
    val facts: List<String> = emptyList(),
    val sourceUrls: List<String> = emptyList()
)

