package io.deepsearch.domain.knowledgegraph

import kotlinx.serialization.Serializable

/**
 * Represents an extracted entity from content before resolution.
 * Used during the extraction phase before entity resolution.
 * 
 * @property name The entity name as extracted from content
 * @property type Classification of the entity type
 * @property facts List of facts extracted about this entity
 */
@Serializable
data class ExtractedEntity(
    val name: String,
    val type: EntityType,
    val facts: List<String> = emptyList()
)

/**
 * Represents an extracted relationship from content before resolution.
 * Uses entity names (not IDs) since entities haven't been resolved yet.
 * 
 * @property fromEntity Name of the source entity
 * @property toEntity Name of the target entity
 * @property relationType The type of relationship
 * @property confidence Confidence score of the relationship (0.0-1.0)
 */
@Serializable
data class ExtractedRelationship(
    val fromEntity: String,
    val toEntity: String,
    val relationType: RelationType,
    val confidence: Float = 1.0f
)

/**
 * Result of entity/relationship extraction from a document.
 * Output from the EntityExtractionAgent.
 * 
 * @property entities List of extracted entities with their facts
 * @property relationships List of extracted relationships between entities
 */
@Serializable
data class KgExtractionResult(
    val entities: List<ExtractedEntity>,
    val relationships: List<ExtractedRelationship>
) {
    companion object {
        fun empty() = KgExtractionResult(emptyList(), emptyList())
    }
    
    fun isEmpty(): Boolean = entities.isEmpty() && relationships.isEmpty()
    fun isNotEmpty(): Boolean = !isEmpty()
}

