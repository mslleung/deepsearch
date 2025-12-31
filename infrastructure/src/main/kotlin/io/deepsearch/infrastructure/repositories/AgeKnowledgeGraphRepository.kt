package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.knowledgegraph.*
import io.deepsearch.domain.repositories.IKnowledgeGraphRepository
import io.deepsearch.domain.services.ITextEmbeddingService
import io.deepsearch.infrastructure.database.KgEntityEmbeddingsTable
import io.deepsearch.infrastructure.database.KgEntitySourcesTable
import io.deepsearch.infrastructure.database.KgRelationshipSourcesTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Apache AGE-based implementation of the Knowledge Graph repository.
 * Handles entity/relationship storage, retrieval, and graph operations.
 * 
 * Note: The graph operations (AGE) may not be available in all PostgreSQL installations.
 * When AGE is not available, the repository falls back to relational queries on provenance tables.
 */
@OptIn(ExperimentalTime::class)
class AgeKnowledgeGraphRepository(
    private val entityEmbeddingsTable: KgEntityEmbeddingsTable,
    private val entitySourcesTable: KgEntitySourcesTable,
    private val relationshipSourcesTable: KgRelationshipSourcesTable,
    private val transactionService: ITransactionService,
    private val textEmbeddingService: ITextEmbeddingService
) : IKnowledgeGraphRepository {

    private val logger: Logger = LoggerFactory.getLogger(AgeKnowledgeGraphRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    companion object {
        private const val ENTITY_RESOLUTION_SIMILARITY_THRESHOLD = 0.88f
    }

    // ========================================
    // INDEXING OPERATIONS
    // ========================================

    override suspend fun indexDocument(url: String, extraction: KgExtractionResult) {
        if (extraction.isEmpty()) {
            logger.debug("Empty extraction for URL: {}, skipping indexing", url)
            return
        }

        val now = Clock.System.now().toEpochMilliseconds()

        transactionService.withTransaction {
            // Step 1: Remove old provenance for this URL
            removeProvenanceForUrl(url)

            // Step 2: Resolve entities (find existing or create new)
            val resolvedEntities = mutableMapOf<String, UUID>()
            for (entity in extraction.entities) {
                val entityId = resolveEntity(entity, url, now)
                resolvedEntities[entity.name] = entityId
            }

            // Step 3: Index relationships with provenance
            for (rel in extraction.relationships) {
                val fromId = resolvedEntities[rel.fromEntity]
                val toId = resolvedEntities[rel.toEntity]

                if (fromId != null && toId != null) {
                    indexRelationship(fromId, toId, rel.relationType, rel.confidence, url, now)
                } else {
                    logger.debug(
                        "Skipping relationship {}->{} - entity not resolved (from={}, to={})",
                        rel.fromEntity, rel.toEntity, fromId, toId
                    )
                }
            }
        }

        logger.debug(
            "Indexed {} entities and {} relationships from URL: {}",
            extraction.entities.size,
            extraction.relationships.size,
            url
        )
    }

    override suspend fun removeDocument(url: String) {
        transactionService.withTransaction {
            removeProvenanceForUrl(url)
            garbageCollectOrphanedEntities()
        }
        logger.debug("Removed document from KG: {}", url)
    }

    override suspend fun batchIndexDocuments(extractions: Map<String, KgExtractionResult>) {
        if (extractions.isEmpty()) return

        val now = Clock.System.now().toEpochMilliseconds()

        // Collect all unique entity names for batch embedding
        val allEntities = extractions.values.flatMap { it.entities }
        val uniqueEntityNames = allEntities.map { it.name }.distinct()

        // Pre-generate embeddings for all entities in a single batch
        val embeddingMap = if (uniqueEntityNames.isNotEmpty()) {
            val result = textEmbeddingService.embedDocuments(uniqueEntityNames)
            uniqueEntityNames.zip(result.embeddings).toMap()
        } else {
            emptyMap()
        }

        transactionService.withTransaction {
            for ((url, extraction) in extractions) {
                if (extraction.isEmpty()) continue

                // Remove old provenance
                removeProvenanceForUrl(url)

                // Resolve entities with pre-computed embeddings
                val resolvedEntities = mutableMapOf<String, UUID>()
                for (entity in extraction.entities) {
                    val embedding = embeddingMap[entity.name]
                    val entityId = resolveEntityWithEmbedding(entity, url, now, embedding)
                    resolvedEntities[entity.name] = entityId
                }

                // Index relationships
                for (rel in extraction.relationships) {
                    val fromId = resolvedEntities[rel.fromEntity]
                    val toId = resolvedEntities[rel.toEntity]

                    if (fromId != null && toId != null) {
                        indexRelationship(fromId, toId, rel.relationType, rel.confidence, url, now)
                    }
                }
            }
        }

        logger.debug("Batch indexed {} documents into KG", extractions.size)
    }

    // ========================================
    // QUERY OPERATIONS
    // ========================================

    override suspend fun semanticEntitySearch(
        queryEmbedding: FloatArray,
        limit: Int,
        urlPrefix: String?,
        minExtractedAtEpochMs: Long?
    ): List<KgEntity> {
        return transactionService.withTransaction {
            // Get all entities with embeddings
            val allEntities = entityEmbeddingsTable.selectAll()
                .map { row ->
                    val entityId = row[entityEmbeddingsTable.entityId]
                    val name = row[entityEmbeddingsTable.name]
                    val type = row[entityEmbeddingsTable.type]
                    val embedding = row[entityEmbeddingsTable.embedding]
                    Triple(entityId, "$name|||$type", embedding)
                }
                .toList()
            
            // Filter by URL prefix and/or cache age if specified
            // An entity is included if ANY of its sources match the criteria
            val filteredEntities = if (urlPrefix != null || minExtractedAtEpochMs != null) {
                val validEntityIds = entitySourcesTable.selectAll()
                    .map { row -> 
                        Triple(
                            row[entitySourcesTable.entityId],
                            row[entitySourcesTable.sourceUrl],
                            row[entitySourcesTable.extractedAtEpochMs]
                        )
                    }
                    .toList()
                    .filter { (_, sourceUrl, extractedAt) ->
                        val urlMatches = urlPrefix == null || sourceUrl.startsWith(urlPrefix)
                        val ageMatches = minExtractedAtEpochMs == null || extractedAt >= minExtractedAtEpochMs
                        urlMatches && ageMatches
                    }
                    .map { it.first }
                    .toSet()
                
                allEntities.filter { validEntityIds.contains(it.first) }
            } else {
                allEntities
            }
            
            // Calculate cosine similarity and sort
            val scoredEntities = filteredEntities
                .filter { it.third != null }
                .map { (entityId, nameType, embedding) ->
                    val similarity = cosineSimilarity(queryEmbedding, embedding!!.toFloatArray())
                    Triple(entityId, nameType, similarity)
                }
                .sortedByDescending { it.third }
                .take(limit)
            
            // Fetch full entity details
            scoredEntities.map { (entityId, nameType, _) ->
                val (name, type) = nameType.split("|||")
                fetchEntityDetails(entityId, name, type)
            }
        }
    }

    override suspend fun traverseFromEntities(entityIds: List<UUID>, maxHops: Int): KgSubgraph {
        if (entityIds.isEmpty()) return KgSubgraph(emptyList(), emptyList(), emptyList())

        return transactionService.withTransaction {
            val entities = mutableMapOf<UUID, KgEntity>()
            val relationships = mutableListOf<KgRelationship>()

            // First, load the starting entities
            for (entityId in entityIds) {
                val entity = loadEntityById(entityId)
                if (entity != null) {
                    entities[entityId] = entity
                }
            }

            // Traverse N hops using the relationship provenance tables
            var currentHop = entityIds.toSet()
            for (hop in 1..maxHops) {
                if (currentHop.isEmpty()) break

                val nextHop = mutableSetOf<UUID>()

                for (fromId in currentHop) {
                    // Query relationships from provenance table
                    val rels = queryRelationshipsFrom(fromId)
                    for ((toId, relationType) in rels) {
                        // Load destination entity if not already loaded
                        if (!entities.containsKey(toId)) {
                            val toEntity = loadEntityById(toId)
                            if (toEntity != null) {
                                entities[toId] = toEntity
                                nextHop.add(toId)
                            }
                        }

                        // Add relationship
                        val fromEntity = entities[fromId]
                        val toEntity = entities[toId]
                        if (fromEntity != null && toEntity != null) {
                            relationships.add(
                                KgRelationship(
                                    fromEntity = fromEntity,
                                    toEntity = toEntity,
                                    relationType = relationType
                                )
                            )
                        }
                    }
                }

                currentHop = nextHop
            }

            KgSubgraph(
                entities = entities.values.toList(),
                relationships = relationships,
                queryEntities = entityIds.map { it.toString() }
            )
        }
    }

    override suspend fun executeCypher(cypherQuery: String, timeoutSeconds: Int): List<Map<String, String>> {
        // Cypher execution requires AGE extension which may not be available
        // For now, return empty results - the repository falls back to relational queries
        logger.debug("Cypher execution not implemented - falling back to empty results")
        return emptyList()
    }

    override suspend fun getSchemaDescription(): String {
        return transactionService.withTransaction {
            val entityTypeCounts = mutableMapOf<String, Int>()
            val relationshipTypeCounts = mutableMapOf<String, Int>()

            // Count entities by type
            entityEmbeddingsTable.selectAll()
                .map { it[entityEmbeddingsTable.type] }
                .toList()
                .forEach { type ->
                    entityTypeCounts[type] = entityTypeCounts.getOrDefault(type, 0) + 1
                }

            // Count relationships by type
            relationshipSourcesTable.selectAll()
                .map { it[relationshipSourcesTable.relationType] }
                .toList()
                .forEach { type ->
                    relationshipTypeCounts[type] = relationshipTypeCounts.getOrDefault(type, 0) + 1
                }

            buildString {
                appendLine("Entity Types:")
                for ((type, count) in entityTypeCounts.entries.sortedByDescending { it.value }) {
                    appendLine("  - $type: $count entities")
                }
                appendLine()
                appendLine("Relationship Types:")
                for ((type, count) in relationshipTypeCounts.entries.sortedByDescending { it.value }) {
                    appendLine("  - $type: $count relationships")
                }
            }
        }
    }

    override suspend fun hasDataForUrlPrefix(urlPrefix: String): Boolean {
        return transactionService.withTransaction {
            entitySourcesTable.selectAll()
                .map { it[entitySourcesTable.sourceUrl] }
                .toList()
                .any { it.startsWith(urlPrefix) }
        }
    }

    // ========================================
    // PRIVATE HELPER METHODS
    // ========================================

    private suspend fun removeProvenanceForUrl(url: String) {
        entitySourcesTable.deleteWhere { entitySourcesTable.sourceUrl eq url }
        relationshipSourcesTable.deleteWhere { relationshipSourcesTable.sourceUrl eq url }
    }

    private suspend fun resolveEntity(
        entity: ExtractedEntity,
        sourceUrl: String,
        now: Long
    ): UUID {
        // Generate embedding for entity name
        val embeddingResult = textEmbeddingService.embedQuery(entity.name)
        return resolveEntityWithEmbedding(entity, sourceUrl, now, embeddingResult.embedding)
    }

    private suspend fun resolveEntityWithEmbedding(
        entity: ExtractedEntity,
        sourceUrl: String,
        now: Long,
        embedding: List<Float>?
    ): UUID {
        val canonicalName = entity.name.lowercase().trim()

        // Step 1: Try exact canonical name + type match
        val existingByName = entityEmbeddingsTable.selectAll()
            .where {
                (entityEmbeddingsTable.canonicalName eq canonicalName) and
                        (entityEmbeddingsTable.type eq entity.type.name)
            }
            .map { it[entityEmbeddingsTable.entityId] }
            .singleOrNull()

        val entityId = existingByName
            ?: if (embedding != null) {
                // Step 2: Try semantic similarity match
                val candidateEntities = entityEmbeddingsTable.selectAll()
                    .where { entityEmbeddingsTable.type eq entity.type.name }
                    .map { row ->
                        val id = row[entityEmbeddingsTable.entityId]
                        val emb = row[entityEmbeddingsTable.embedding]
                        Pair(id, emb)
                    }
                    .toList()

                val similarEntity = candidateEntities
                    .map { (id, emb) ->
                        val similarity = cosineSimilarity(embedding.toFloatArray(), emb!!.toFloatArray())
                        Pair(id, similarity)
                    }
                    .filter { it.second >= ENTITY_RESOLUTION_SIMILARITY_THRESHOLD }
                    .maxByOrNull { it.second }
                    ?.first

                similarEntity ?: createNewEntity(entity, canonicalName, embedding, now)
            } else {
                createNewEntity(entity, canonicalName, embedding, now)
            }

        // Record provenance
        entitySourcesTable.upsert(
            entitySourcesTable.entityId,
            entitySourcesTable.sourceUrl
        ) {
            it[entitySourcesTable.entityId] = entityId
            it[entitySourcesTable.sourceUrl] = sourceUrl
            it[facts] = json.encodeToString(entity.facts)
            it[extractedAtEpochMs] = now
        }

        return entityId
    }

    private suspend fun createNewEntity(
        entity: ExtractedEntity,
        canonicalName: String,
        embedding: List<Float>?,
        now: Long
    ): UUID {
        val entityId = UUID.randomUUID()

        entityEmbeddingsTable.insert {
            it[entityEmbeddingsTable.entityId] = entityId
            it[name] = entity.name
            it[type] = entity.type.name
            it[entityEmbeddingsTable.canonicalName] = canonicalName
            if (embedding != null) {
                it[entityEmbeddingsTable.embedding] = embedding
            }
            it[createdAtEpochMs] = now
            it[updatedAtEpochMs] = now
        }

        return entityId
    }

    private suspend fun indexRelationship(
        fromId: UUID,
        toId: UUID,
        relationType: RelationType,
        confidence: Float,
        sourceUrl: String,
        now: Long
    ) {
        // Record provenance
        relationshipSourcesTable.upsert(
            relationshipSourcesTable.fromEntityId,
            relationshipSourcesTable.toEntityId,
            relationshipSourcesTable.relationType,
            relationshipSourcesTable.sourceUrl
        ) {
            it[fromEntityId] = fromId
            it[toEntityId] = toId
            it[relationshipSourcesTable.relationType] = relationType.name
            it[relationshipSourcesTable.sourceUrl] = sourceUrl
            it[relationshipSourcesTable.confidence] = confidence
            it[extractedAtEpochMs] = now
        }
    }

    private suspend fun garbageCollectOrphanedEntities() {
        // Find entities with no remaining sources
        val allEntityIds = entityEmbeddingsTable.selectAll()
            .map { it[entityEmbeddingsTable.entityId] }
            .toList()
            
        val entitiesWithSources = entitySourcesTable.selectAll()
            .map { it[entitySourcesTable.entityId] }
            .toList()
            .toSet()
            
        val orphanedIds = allEntityIds.filter { !entitiesWithSources.contains(it) }

        // Delete orphaned entities
        for (entityId in orphanedIds) {
            entityEmbeddingsTable.deleteWhere { entityEmbeddingsTable.entityId eq entityId }
        }

        if (orphanedIds.isNotEmpty()) {
            logger.debug("Garbage collected {} orphaned entities", orphanedIds.size)
        }
    }

    private suspend fun fetchEntityDetails(entityId: UUID, name: String, type: String): KgEntity {
        // Fetch facts from all sources
        val allFacts = entitySourcesTable.selectAll()
            .where { entitySourcesTable.entityId eq entityId }
            .map { row ->
                val factsJson = row[entitySourcesTable.facts]
                try {
                    json.decodeFromString<List<String>>(factsJson)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            .toList()
            .flatten()
            .distinct()

        // Fetch source URLs
        val sourceUrls = entitySourcesTable.selectAll()
            .where { entitySourcesTable.entityId eq entityId }
            .map { it[entitySourcesTable.sourceUrl] }
            .toList()

        return KgEntity(
            id = entityId.toString(),
            name = name,
            type = try {
                EntityType.valueOf(type)
            } catch (e: Exception) {
                EntityType.OTHER
            },
            facts = allFacts,
            sourceUrls = sourceUrls
        )
    }

    private suspend fun loadEntityById(entityId: UUID): KgEntity? {
        val row = entityEmbeddingsTable.selectAll()
            .where { entityEmbeddingsTable.entityId eq entityId }
            .singleOrNull()
            ?: return null

        return fetchEntityDetails(
            entityId,
            row[entityEmbeddingsTable.name],
            row[entityEmbeddingsTable.type]
        )
    }

    private suspend fun queryRelationshipsFrom(fromId: UUID): List<Pair<UUID, RelationType>> {
        return relationshipSourcesTable.selectAll()
            .where { relationshipSourcesTable.fromEntityId eq fromId }
            .map { row ->
                val toId = row[relationshipSourcesTable.toEntityId]
                val type = try {
                    RelationType.valueOf(row[relationshipSourcesTable.relationType])
                } catch (e: Exception) {
                    RelationType.RELATED_TO
                }
                toId to type
            }
            .toList()
            .distinctBy { it.first to it.second }
    }

    /**
     * Calculate cosine similarity between two vectors.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        val magnitude = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (magnitude > 0) dotProduct / magnitude else 0f
    }
}
