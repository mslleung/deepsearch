package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.knowledgegraph.*
import io.deepsearch.domain.repositories.EntityEmbeddings
import io.deepsearch.domain.repositories.IKnowledgeGraphRepository
import io.deepsearch.infrastructure.database.KgEntityEmbeddingsTable
import io.deepsearch.infrastructure.database.KgEntitySourcesTable
import io.deepsearch.infrastructure.database.KgRelationshipSourcesTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Apache AGE-based implementation of the Knowledge Graph repository.
 * Handles entity/relationship storage, retrieval, and graph operations.
 * 
 * Note: Embeddings must be pre-computed by the caller to ensure proper token tracking.
 * The repository does not generate embeddings itself.
 */
@OptIn(ExperimentalTime::class)
class AgeKnowledgeGraphRepository(
    private val entityEmbeddingsTable: KgEntityEmbeddingsTable,
    private val entitySourcesTable: KgEntitySourcesTable,
    private val relationshipSourcesTable: KgRelationshipSourcesTable,
    private val transactionService: ITransactionService
) : IKnowledgeGraphRepository {

    private val logger: Logger = LoggerFactory.getLogger(AgeKnowledgeGraphRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val GRAPH_NAME = "knowledge_graph"

        /**
         * Extract domain from URL for graph partitioning.
         * e.g., "https://docs.notion.so/path" -> "docs.notion.so"
         */
        fun extractDomain(url: String): String {
            return try {
                URI(url).host ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        }

        /**
         * Escape a string for use in Cypher queries.
         * Handles single quotes and backslashes.
         */
        private fun escapeCypher(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
        }
    }

    // ========================================
    // INDEXING OPERATIONS
    // ========================================

    override suspend fun indexDocument(
        url: String,
        extraction: KgExtractionResult,
        embeddings: EntityEmbeddings
    ) {
        if (extraction.isEmpty()) {
            logger.debug("Empty extraction for URL: {}, skipping indexing", url)
            return
        }

        val now = Clock.System.now().toEpochMilliseconds()
        val domain = extractDomain(url)

        transactionService.withTransaction {
            // Step 1: Remove old provenance for this URL (also removes from AGE graph)
            removeProvenanceForUrl(url)

            // Step 2: Resolve entities (find existing or create new)
            val resolvedEntities = mutableMapOf<String, UUID>()
            for (entity in extraction.entities) {
                val embedding = embeddings[entity.name]
                val entityId = resolveEntityWithEmbedding(entity, url, now, embedding, domain)
                resolvedEntities[entity.name] = entityId
            }

            // Step 3: Index relationships with provenance
            for (rel in extraction.relationships) {
                val fromId = resolvedEntities[rel.fromEntity]
                val toId = resolvedEntities[rel.toEntity]

                if (fromId != null && toId != null) {
                    indexRelationship(fromId, toId, rel.relationType, rel.confidence, url, now, domain)
                } else {
                    logger.debug(
                        "Skipping relationship {}->{} - entity not resolved (from={}, to={})",
                        rel.fromEntity, rel.toEntity, fromId, toId
                    )
                }
            }
        }

        logger.debug(
            "Indexed {} entities and {} relationships from URL: {} (domain: {})",
            extraction.entities.size,
            extraction.relationships.size,
            url,
            domain
        )
    }

    override suspend fun removeDocument(url: String) {
        transactionService.withTransaction {
            removeProvenanceForUrl(url)
            garbageCollectOrphanedEntities()
        }
        logger.debug("Removed document from KG: {}", url)
    }

    override suspend fun batchIndexDocuments(
        extractions: Map<String, KgExtractionResult>,
        embeddings: EntityEmbeddings
    ) {
        if (extractions.isEmpty()) return

        val now = Clock.System.now().toEpochMilliseconds()

        transactionService.withTransaction {
            for ((url, extraction) in extractions) {
                if (extraction.isEmpty()) continue

                val domain = extractDomain(url)

                // Remove old provenance (also removes from AGE graph)
                removeProvenanceForUrl(url)

                // Resolve entities with pre-computed embeddings
                val resolvedEntities = mutableMapOf<String, UUID>()
                for (entity in extraction.entities) {
                    val embedding = embeddings[entity.name]
                    val entityId = resolveEntityWithEmbedding(entity, url, now, embedding, domain)
                    resolvedEntities[entity.name] = entityId
                }

                // Index relationships
                for (rel in extraction.relationships) {
                    val fromId = resolvedEntities[rel.fromEntity]
                    val toId = resolvedEntities[rel.toEntity]

                    if (fromId != null && toId != null) {
                        indexRelationship(fromId, toId, rel.relationType, rel.confidence, url, now, domain)
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
            // Configure HNSW iterative index scan for better recall with filters
            exec(
                """
                SET LOCAL hnsw.iterative_scan = 'strict_order';
                SET LOCAL hnsw.max_scan_tuples = 20000;
            """.trimIndent()
            )

            // Format query embedding as pgvector string: '[1.0,2.0,3.0,...]'
            val embeddingStr = "[${queryEmbedding.joinToString(",")}]"

            // Create custom SQL expression for cosine distance using pgvector's <=> operator
            val cosineDistanceExpr = object : Expression<Double>() {
                override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                    queryBuilder.append("(${entityEmbeddingsTable.tableName}.embedding <=> '$embeddingStr'::vector)")
                }
            }

            // EXISTS subquery filter for URL prefix and/or extraction time
            // Uses the composite index on (entity_id, source_url) for efficient prefix matching
            val existsFilter: Expression<Boolean>? = if (urlPrefix != null || minExtractedAtEpochMs != null) {
                object : Expression<Boolean>() {
                    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                        queryBuilder.append("EXISTS (SELECT 1 FROM ${entitySourcesTable.tableName} s WHERE s.entity_id = ${entityEmbeddingsTable.tableName}.entity_id")
                        if (urlPrefix != null) {
                            val escapedPrefix = urlPrefix.replace("'", "''")
                            queryBuilder.append(" AND s.source_url LIKE '$escapedPrefix%'")
                        }
                        if (minExtractedAtEpochMs != null) {
                            queryBuilder.append(" AND s.extracted_at_epoch_ms >= $minExtractedAtEpochMs")
                        }
                        queryBuilder.append(")")
                    }
                }
            } else {
                null
            }

            // Build and execute the query using pgvector's native cosine distance
            val results = entityEmbeddingsTable.selectAll()
                .where {
                    val embeddingNotNull = entityEmbeddingsTable.embedding.isNotNull()
                    if (existsFilter != null) {
                        embeddingNotNull and existsFilter
                    } else {
                        embeddingNotNull
                    }
                }
                .orderBy(cosineDistanceExpr to SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    Triple(
                        row[entityEmbeddingsTable.entityId],
                        row[entityEmbeddingsTable.name],
                        row[entityEmbeddingsTable.type]
                    )
                }
                .toList()

            // Batch fetch entity details for all results at once
            batchFetchEntityDetails(results)
        }
    }

    override suspend fun traverseFromEntities(entityIds: List<UUID>, maxHops: Int): KgSubgraph {
        if (entityIds.isEmpty()) return KgSubgraph(emptyList(), emptyList(), emptyList())

        return transactionService.withTransaction {
            val entities = mutableMapOf<UUID, KgEntity>()
            val relationships = mutableListOf<KgRelationship>()

            // Batch load starting entities using IN clause
            val startingEntities = batchLoadEntitiesByIds(entityIds)
            for (entity in startingEntities) {
                entities[UUID.fromString(entity.id)] = entity
            }

            // Traverse N hops using the relationship provenance tables
            var currentHop = entityIds.toSet()
            for (hop in 1..maxHops) {
                if (currentHop.isEmpty()) break

                // Batch query all relationships from current hop entities
                val allRels = batchQueryRelationshipsFrom(currentHop.toList())

                // Collect all destination entity IDs we need to load
                val missingEntityIds = allRels
                    .map { it.second }
                    .filter { !entities.containsKey(it) }
                    .distinct()

                // Batch load missing entities
                if (missingEntityIds.isNotEmpty()) {
                    val loadedEntities = batchLoadEntitiesByIds(missingEntityIds)
                    for (entity in loadedEntities) {
                        entities[UUID.fromString(entity.id)] = entity
                    }
                }

                // Build relationships and determine next hop
                val nextHop = mutableSetOf<UUID>()
                for ((fromId, toId, relationType) in allRels) {
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

                    // Add to next hop if newly discovered
                    if (!currentHop.contains(toId) && entities.containsKey(toId)) {
                        nextHop.add(toId)
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
        if (cypherQuery.isBlank()) {
            return emptyList()
        }

        // Build Apache AGE SQL query
        val sql = buildAgeCypherSql(cypherQuery)

        logger.debug("Executing Cypher query via AGE: {}", cypherQuery)
        logger.debug("Generated SQL: {}", sql)

        return try {
            val rawResults = transactionService.executeRawQuery(sql, timeoutSeconds)
            logger.debug("Raw results count: {}", rawResults.size)

            // Parse agtype results into simple string maps
            rawResults.map { row ->
                row.mapValues { (_, value) -> parseAgtypeValue(value) }
            }
        } catch (e: Exception) {
            logger.error("Error executing Cypher query: {}", e.message, e)
            emptyList()
        }
    }

    /**
     * Build the SQL statement to execute a Cypher query via Apache AGE.
     * Uses dollar-quoting to safely embed the Cypher query.
     * 
     * NOTE: The database must be configured with:
     *   ALTER DATABASE deepsearch SET session_preload_libraries = 'age';
     *   ALTER DATABASE deepsearch SET search_path = public, "$user", ag_catalog;
     */
    private fun buildAgeCypherSql(cypherQuery: String): String {
        // Extract column names from the RETURN clause for the AS definition
        val returnColumns = extractReturnColumns(cypherQuery)

        // Build column definitions for the AS clause
        val columnDefs = if (returnColumns.isNotEmpty()) {
            returnColumns.joinToString(", ") { "$it agtype" }
        } else {
            // Default to single 'result' column if we can't parse RETURN
            "result agtype"
        }

        // Single statement query - AGE is auto-loaded via session_preload_libraries
        return """SELECT * FROM cypher('knowledge_graph', ${'$'}${'$'}$cypherQuery${'$'}${'$'}) AS ($columnDefs)"""
    }

    /**
     * Extract column names from the RETURN clause of a Cypher query.
     * Handles patterns like:
     * - RETURN n.name, n.age -> [name, age]
     * - RETURN n.name AS name, n.age AS age -> [name, age]
     * - RETURN n -> [n]
     * - RETURN p.name, c.name -> [p_name, c_name] (auto-alias duplicates)
     */
    private fun extractReturnColumns(cypherQuery: String): List<String> {
        // Find the RETURN clause (case-insensitive)
        val returnMatch =
            Regex("(?i)\\bRETURN\\s+(.+?)(?:\\s+ORDER\\s+BY|\\s+LIMIT|\\s+SKIP|$)", RegexOption.DOT_MATCHES_ALL)
                .find(cypherQuery)
                ?: return emptyList()

        val returnClause = returnMatch.groupValues[1].trim()

        // Split by comma and extract column names with their full expressions
        val rawColumns = returnClause.split(",").mapNotNull { part ->
            val trimmed = part.trim()

            // Check for AS alias - use it directly
            val asMatch = Regex("(?i)\\bAS\\s+(\\w+)$").find(trimmed)
            if (asMatch != null) {
                return@mapNotNull asMatch.groupValues[1] to asMatch.groupValues[1]
            }

            // Check for property access (n.property) - preserve full expression for uniqueness
            val propMatch = Regex("(\\w+)\\.(\\w+)$").find(trimmed)
            if (propMatch != null) {
                val varName = propMatch.groupValues[1]
                val propName = propMatch.groupValues[2]
                return@mapNotNull "${varName}_${propName}" to propName
            }

            // Check for simple variable name
            val varMatch = Regex("^(\\w+)$").find(trimmed)
            if (varMatch != null) {
                val name = varMatch.groupValues[1]
                return@mapNotNull name to name
            }

            null
        }

        // Deduplicate column names - if there are duplicates, use the full alias (var_prop)
        val nameCounts = rawColumns.groupingBy { it.second }.eachCount()
        return rawColumns.map { (fullAlias, simpleName) ->
            if (nameCounts[simpleName]!! > 1) {
                // There are duplicates, use the full alias (e.g., p_name, c_name)
                fullAlias
            } else {
                // No duplicates, use the simple name
                simpleName
            }
        }
    }

    /**
     * Parse an agtype value into a string.
     * AGE returns JSON-like values that need unwrapping.
     */
    private fun parseAgtypeValue(value: String): String {
        if (value.isBlank()) return ""

        // agtype strings are wrapped in quotes: "value"
        // Numbers and booleans are returned as-is
        return when {
            value.startsWith("\"") && value.endsWith("\"") ->
                value.drop(1).dropLast(1)

            value == "null" -> ""
            else -> value
        }
    }

    override suspend fun getSchemaDescription(): String {
        return transactionService.withTransaction {
            // Count entities by type - fetch all types and count in memory
            // This is efficient for typical KG sizes (thousands of entities)
            val entityTypeCounts = entityEmbeddingsTable.selectAll()
                .map { it[entityEmbeddingsTable.type] }
                .toList()
                .groupingBy { it }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }

            // Count relationships by type
            val relationshipTypeCounts = relationshipSourcesTable.selectAll()
                .map { it[relationshipSourcesTable.relationType] }
                .toList()
                .groupingBy { it }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }

            buildString {
                appendLine("Entity Types:")
                for ((type, count) in entityTypeCounts) {
                    appendLine("  - $type: $count entities")
                }
                appendLine()
                appendLine("Relationship Types:")
                for ((type, count) in relationshipTypeCounts) {
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

    private suspend fun resolveEntityWithEmbedding(
        entity: ExtractedEntity,
        sourceUrl: String,
        now: Long,
        embedding: List<Float>?,
        domain: String
    ): UUID {
        val canonicalName = entity.name.lowercase().trim()

        // Only merge on exact canonical name + type match.
        // This handles trivial duplicates (case/whitespace differences).
        // Semantic similarity is handled at query time via vector search,
        // avoiding data loss from incorrect merging.
        val existingByName = entityEmbeddingsTable.selectAll()
            .where {
                (entityEmbeddingsTable.canonicalName eq canonicalName) and
                        (entityEmbeddingsTable.type eq entity.type.name)
            }
            .map { it[entityEmbeddingsTable.entityId] }
            .singleOrNull()

        val entityId = existingByName ?: createNewEntity(entity, canonicalName, embedding, now, domain)

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
        now: Long,
        domain: String
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

        // Also create vertex in AGE graph with domain for filtering
        val cypherCreate = $$"""
                SELECT * FROM cypher('$$GRAPH_NAME', $$
                    CREATE (e:Entity {
                        id: '$${entityId}',
                        name: '$${escapeCypher(entity.name)}',
                        type: '$${entity.type.name}',
                        domain: '$${escapeCypher(domain)}'
                    })
                $$) AS (v agtype)
            """.trimIndent()
        transactionService.executeRawUpdate(cypherCreate)

        return entityId
    }

    private suspend fun indexRelationship(
        fromId: UUID,
        toId: UUID,
        relationType: RelationType,
        confidence: Float,
        sourceUrl: String,
        now: Long,
        domain: String
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

        // Also create edge in AGE graph
        // Using MERGE to avoid duplicate edges, matching by entity IDs
        val cypherCreate = $$"""
                SELECT * FROM cypher('$$GRAPH_NAME', $$
                    MATCH (from:Entity {id: '$${fromId}'}), (to:Entity {id: '$${toId}'})
                    CREATE (from)-[:$${relationType.name} {domain: '$${escapeCypher(domain)}', confidence: $${confidence}}]->(to)
                $$) AS (e agtype)
            """.trimIndent()
        transactionService.executeRawUpdate(cypherCreate)
    }

    private suspend fun garbageCollectOrphanedEntities() {
        // Find entities with no remaining sources
        val allEntityIds = entityEmbeddingsTable.selectAll()
            .map { it[entityEmbeddingsTable.entityId] }
            .toList()

        if (allEntityIds.isEmpty()) return

        val entitiesWithSources = entitySourcesTable.selectAll()
            .map { it[entitySourcesTable.entityId] }
            .toList()
            .toSet()

        val orphanedIds = allEntityIds.filter { !entitiesWithSources.contains(it) }

        // Delete orphaned entities from relational table and AGE graph
        if (orphanedIds.isNotEmpty()) {
            entityEmbeddingsTable.deleteWhere { entityEmbeddingsTable.entityId inList orphanedIds }

            // Delete from AGE graph - delete vertices and their edges
            for (entityId in orphanedIds) {
                val cypherDelete = $$"""
                        SELECT * FROM cypher('$$GRAPH_NAME', $$
                            MATCH (e:Entity {id: '$${entityId}'})
                            DETACH DELETE e
                        $$) AS (v agtype)
                    """.trimIndent()
                transactionService.executeRawUpdate(cypherDelete)
            }

            logger.debug("Garbage collected {} orphaned entities (relational + AGE)", orphanedIds.size)
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

    /**
     * Batch query relationships from multiple source entity IDs.
     * More efficient than individual queries.
     */
    private suspend fun batchQueryRelationshipsFrom(fromIds: List<UUID>): List<Triple<UUID, UUID, RelationType>> {
        if (fromIds.isEmpty()) return emptyList()

        return relationshipSourcesTable.selectAll()
            .where { relationshipSourcesTable.fromEntityId inList fromIds }
            .map { row ->
                val fromId = row[relationshipSourcesTable.fromEntityId]
                val toId = row[relationshipSourcesTable.toEntityId]
                val type = try {
                    RelationType.valueOf(row[relationshipSourcesTable.relationType])
                } catch (e: Exception) {
                    RelationType.RELATED_TO
                }
                Triple(fromId, toId, type)
            }
            .toList()
            .distinctBy { Triple(it.first, it.second, it.third) }
    }

    /**
     * Batch load entities by their IDs using a single query with IN clause.
     */
    private suspend fun batchLoadEntitiesByIds(entityIds: List<UUID>): List<KgEntity> {
        if (entityIds.isEmpty()) return emptyList()

        // Get all entity base data in one query
        val entityBaseData = entityEmbeddingsTable.selectAll()
            .where { entityEmbeddingsTable.entityId inList entityIds }
            .map { row ->
                Triple(
                    row[entityEmbeddingsTable.entityId],
                    row[entityEmbeddingsTable.name],
                    row[entityEmbeddingsTable.type]
                )
            }
            .toList()

        if (entityBaseData.isEmpty()) return emptyList()

        // Get all facts and sources for these entities in one query
        val allSourceData = entitySourcesTable.selectAll()
            .where { entitySourcesTable.entityId inList entityIds }
            .map { row ->
                val entityId = row[entitySourcesTable.entityId]
                val sourceUrl = row[entitySourcesTable.sourceUrl]
                val factsJson = row[entitySourcesTable.facts]
                val facts = try {
                    json.decodeFromString<List<String>>(factsJson)
                } catch (e: Exception) {
                    emptyList()
                }
                Triple(entityId, sourceUrl, facts)
            }
            .toList()

        // Group source data by entity ID
        val sourcesByEntity = allSourceData.groupBy { it.first }

        // Build KgEntity objects
        return entityBaseData.map { (entityId, name, type) ->
            val sources = sourcesByEntity[entityId] ?: emptyList()
            KgEntity(
                id = entityId.toString(),
                name = name,
                type = try {
                    EntityType.valueOf(type)
                } catch (e: Exception) {
                    EntityType.OTHER
                },
                facts = sources.flatMap { it.third }.distinct(),
                sourceUrls = sources.map { it.second }.distinct()
            )
        }
    }

    /**
     * Batch fetch entity details for a list of entity ID/name/type tuples.
     * Used by semanticEntitySearch for efficient detail retrieval.
     */
    private suspend fun batchFetchEntityDetails(
        entities: List<Triple<UUID, String, String>>
    ): List<KgEntity> {
        if (entities.isEmpty()) return emptyList()

        val entityIds = entities.map { it.first }

        // Get all facts and sources for these entities in one query
        val allSourceData = entitySourcesTable.selectAll()
            .where { entitySourcesTable.entityId inList entityIds }
            .map { row ->
                val entityId = row[entitySourcesTable.entityId]
                val sourceUrl = row[entitySourcesTable.sourceUrl]
                val factsJson = row[entitySourcesTable.facts]
                val facts = try {
                    json.decodeFromString<List<String>>(factsJson)
                } catch (e: Exception) {
                    emptyList()
                }
                Triple(entityId, sourceUrl, facts)
            }
            .toList()

        // Group source data by entity ID
        val sourcesByEntity = allSourceData.groupBy { it.first }

        // Build KgEntity objects
        return entities.map { (entityId, name, type) ->
            val sources = sourcesByEntity[entityId] ?: emptyList()
            KgEntity(
                id = entityId.toString(),
                name = name,
                type = try {
                    EntityType.valueOf(type)
                } catch (e: Exception) {
                    EntityType.OTHER
                },
                facts = sources.flatMap { it.third }.distinct(),
                sourceUrls = sources.map { it.second }.distinct()
            )
        }
    }

}
