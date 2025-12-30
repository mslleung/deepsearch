package io.deepsearch.domain.knowledgegraph

import kotlinx.serialization.Serializable

/**
 * Represents a relationship between two entities in the knowledge graph.
 * 
 * @property fromEntity The source entity of the relationship
 * @property toEntity The target entity of the relationship
 * @property relationType The type of relationship
 * @property confidence Confidence score of the relationship (0.0-1.0)
 */
@Serializable
data class KgRelationship(
    val fromEntity: KgEntity,
    val toEntity: KgEntity,
    val relationType: RelationType,
    val confidence: Float = 1.0f
)

