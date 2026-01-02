package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.database.types.vector
import org.jetbrains.exposed.v1.core.Table

/**
 * Table for storing entity metadata and embeddings.
 * Used for semantic entity search during query time.
 */
class KgEntityEmbeddingsTable : Table("kg_entity_embeddings") {
    val entityId = uuid("entity_id")
    val name = text("name")
    val type = varchar("type", length = 50)
    val canonicalName = varchar("canonical_name", length = 512)
    val embedding = vector("embedding", dimensions = 1536)  // Entity name embeddings (gemini-embedding-001)
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    
    init {
        index(false, canonicalName, type)
    }
    
    override val primaryKey = PrimaryKey(entityId)
}

/**
 * Provenance tracking for entities.
 * Tracks which source URLs contributed to each entity.
 */
class KgEntitySourcesTable : Table("kg_entity_sources") {
    val entityId = uuid("entity_id")
    val sourceUrl = varchar("source_url", length = 2048)
    val facts = text("facts")  // JSON array of facts
    val extractedAtEpochMs = long("extracted_at_epoch_ms")
    
    init {
        index(false, sourceUrl)
        // Composite index for efficient EXISTS subquery in semantic search
        // (entityId lookup + sourceUrl prefix matching)
        index(false, entityId, sourceUrl)
    }
    
    override val primaryKey = PrimaryKey(entityId, sourceUrl)
}

/**
 * Provenance tracking for relationships.
 * Tracks which source URLs contributed to each relationship.
 */
class KgRelationshipSourcesTable : Table("kg_relationship_sources") {
    val fromEntityId = uuid("from_entity_id")
    val toEntityId = uuid("to_entity_id")
    val relationType = varchar("relation_type", length = 50)
    val sourceUrl = varchar("source_url", length = 2048)
    val confidence = float("confidence").default(1.0f)
    val extractedAtEpochMs = long("extracted_at_epoch_ms")
    
    init {
        index(false, sourceUrl)
    }
    
    override val primaryKey = PrimaryKey(fromEntityId, toEntityId, relationType, sourceUrl)
}

