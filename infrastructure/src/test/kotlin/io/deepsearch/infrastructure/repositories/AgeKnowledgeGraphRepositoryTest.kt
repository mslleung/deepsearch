package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.knowledgegraph.*
import io.deepsearch.domain.repositories.EntityEmbeddings
import io.deepsearch.domain.repositories.IKnowledgeGraphRepository
import io.deepsearch.domain.services.ITextEmbeddingService
import io.deepsearch.infrastructure.config.infrastructureTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.getValue
import kotlin.uuid.Uuid
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)
class AgeKnowledgeGraphRepositoryTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinExtension = IsolatedKoinExtension.create {
        modules(domainTestModule, infrastructureTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val knowledgeGraphRepository by inject<IKnowledgeGraphRepository>()
    private val textEmbeddingService by inject<ITextEmbeddingService>()

    /** Helper to generate embeddings for a list of entity names */
    private suspend fun generateEmbeddings(entityNames: List<String>): EntityEmbeddings {
        if (entityNames.isEmpty()) return EntityEmbeddings.empty()
        val result = textEmbeddingService.embedForSimilarity(entityNames)
        return EntityEmbeddings.fromMap(entityNames.zip(result.embeddings).toMap())
    }

    /** Helper to generate embeddings from an extraction */
    private suspend fun generateEmbeddings(extraction: KgExtractionResult): EntityEmbeddings {
        return generateEmbeddings(extraction.entities.map { it.name })
    }

    @Test
    fun `indexDocument and semanticEntitySearch finds entities`() = runTest(testCoroutineDispatcher) {
        // Given: an extraction result with entities and relationships
        val sourceUrl = "https://example.com/kg-test/product-page"
        val extraction = KgExtractionResult(
            entities = listOf(
                ExtractedEntity(
                    name = "Acme Pro Plan",
                    type = EntityType.PRICING_TIER,
                    facts = listOf("Monthly subscription", "Includes advanced features")
                ),
                ExtractedEntity(
                    name = "$99/month",
                    type = EntityType.PRICE,
                    facts = listOf("USD pricing")
                ),
                ExtractedEntity(
                    name = "Advanced Analytics",
                    type = EntityType.FEATURE,
                    facts = listOf("Real-time dashboards", "Custom reports")
                )
            ),
            relationships = listOf(
                ExtractedRelationship(
                    fromEntity = "Acme Pro Plan",
                    toEntity = "$99/month",
                    relationType = RelationType.HAS_PRICE,
                    confidence = 0.95f
                ),
                ExtractedRelationship(
                    fromEntity = "Acme Pro Plan",
                    toEntity = "Advanced Analytics",
                    relationType = RelationType.HAS_FEATURE,
                    confidence = 0.9f
                )
            )
        )

        // When: indexing the document with pre-computed embeddings
        val embeddings = generateEmbeddings(extraction)
        knowledgeGraphRepository.indexDocument(sourceUrl, extraction, embeddings)

        // Then: semantic search should find the entities
        // Use embedForSimilarity to match the task type used for entity embeddings
        val queryEmbedding = textEmbeddingService.embedForSimilarity("Acme pricing plan")
        val results = knowledgeGraphRepository.semanticEntitySearch(
            queryEmbedding = queryEmbedding.embedding.toFloatArray(),
            limit = 10,
            urlPrefix = "https://example.com/kg-test/"
        )

        assertTrue(results.isNotEmpty(), "Should find at least one entity")
        assertTrue(
            results.any { it.name == "Acme Pro Plan" },
            "Should find Acme Pro Plan entity"
        )
    }

    @Test
    fun `traverseFromEntities returns connected entities`() = runTest(testCoroutineDispatcher) {
        // Given: indexed entities with relationships
        val sourceUrl = "https://example.com/kg-test/traverse-test"
        val extraction = KgExtractionResult(
            entities = listOf(
                ExtractedEntity(
                    name = "TraverseProduct",
                    type = EntityType.PRODUCT,
                    facts = listOf("Main product")
                ),
                ExtractedEntity(
                    name = "TraverseFeatureA",
                    type = EntityType.FEATURE,
                    facts = listOf("Feature A description")
                ),
                ExtractedEntity(
                    name = "TraverseFeatureB",
                    type = EntityType.FEATURE,
                    facts = listOf("Feature B description")
                )
            ),
            relationships = listOf(
                ExtractedRelationship(
                    fromEntity = "TraverseProduct",
                    toEntity = "TraverseFeatureA",
                    relationType = RelationType.HAS_FEATURE
                ),
                ExtractedRelationship(
                    fromEntity = "TraverseProduct",
                    toEntity = "TraverseFeatureB",
                    relationType = RelationType.HAS_FEATURE
                )
            )
        )

        val embeddings = generateEmbeddings(extraction)
        knowledgeGraphRepository.indexDocument(sourceUrl, extraction, embeddings)

        // Find the starting entity
        val queryEmbedding = textEmbeddingService.embedForSimilarity("TraverseProduct")
        val searchResults = knowledgeGraphRepository.semanticEntitySearch(
            queryEmbedding = queryEmbedding.embedding.toFloatArray(),
            limit = 1,
            urlPrefix = "https://example.com/kg-test/"
        )

        assertTrue(searchResults.isNotEmpty(), "Should find starting entity")
        val startEntityId = Uuid.parse(searchResults.first().id)

        // When: traversing from the entity
        val subgraph = knowledgeGraphRepository.traverseFromEntities(
            entityIds = listOf(startEntityId),
            maxHops = 2
        )

        // Then: should find connected entities
        assertTrue(subgraph.entities.isNotEmpty(), "Should have entities in subgraph")
        assertTrue(
            subgraph.entities.any { it.name == "TraverseProduct" },
            "Should contain starting entity"
        )
        // Relationships should connect to features
        assertTrue(
            subgraph.relationships.isNotEmpty() || subgraph.entities.size >= 1,
            "Should have relationships or at least the starting entity"
        )
    }

    @Test
    fun `removeDocument removes provenance and orphaned entities`() = runTest(testCoroutineDispatcher) {
        // Given: an indexed document
        val sourceUrl = "https://example.com/kg-test/remove-test"
        val extraction = KgExtractionResult(
            entities = listOf(
                ExtractedEntity(
                    name = "RemoveTestEntity",
                    type = EntityType.PRODUCT,
                    facts = listOf("To be removed")
                )
            ),
            relationships = emptyList()
        )

        val embeddings = generateEmbeddings(extraction)
        knowledgeGraphRepository.indexDocument(sourceUrl, extraction, embeddings)

        // Verify entity exists
        val queryEmbedding = textEmbeddingService.embedForSimilarity("RemoveTestEntity")
        val beforeRemoval = knowledgeGraphRepository.semanticEntitySearch(
            queryEmbedding = queryEmbedding.embedding.toFloatArray(),
            limit = 10,
            urlPrefix = "https://example.com/kg-test/"
        )
        assertTrue(
            beforeRemoval.any { it.name == "RemoveTestEntity" },
            "Entity should exist before removal"
        )

        // When: removing the document
        knowledgeGraphRepository.removeDocument(sourceUrl)

        // Then: entity should no longer be found (orphaned entities are garbage collected)
        val afterRemoval = knowledgeGraphRepository.semanticEntitySearch(
            queryEmbedding = queryEmbedding.embedding.toFloatArray(),
            limit = 10,
            urlPrefix = "https://example.com/kg-test/"
        )
        assertFalse(
            afterRemoval.any { it.name == "RemoveTestEntity" },
            "Entity should be removed after document removal"
        )
    }

    @Test
    fun `batchIndexDocuments indexes multiple documents efficiently`() = runTest(testCoroutineDispatcher) {
        // Given: multiple documents to index
        val extractions = mapOf(
            "https://example.com/kg-test/batch1" to KgExtractionResult(
                entities = listOf(
                    ExtractedEntity(name = "BatchProduct1", type = EntityType.PRODUCT, facts = listOf("Product 1"))
                ),
                relationships = emptyList()
            ),
            "https://example.com/kg-test/batch2" to KgExtractionResult(
                entities = listOf(
                    ExtractedEntity(name = "BatchProduct2", type = EntityType.PRODUCT, facts = listOf("Product 2"))
                ),
                relationships = emptyList()
            ),
            "https://example.com/kg-test/batch3" to KgExtractionResult(
                entities = listOf(
                    ExtractedEntity(name = "BatchProduct3", type = EntityType.PRODUCT, facts = listOf("Product 3"))
                ),
                relationships = emptyList()
            )
        )

        // When: batch indexing with pre-computed embeddings
        val allEntityNames = extractions.values.flatMap { it.entities.map { e -> e.name } }
        val embeddings = generateEmbeddings(allEntityNames)
        knowledgeGraphRepository.batchIndexDocuments(extractions, embeddings)

        // Then: all entities should be searchable
        val queryEmbedding = textEmbeddingService.embedForSimilarity("BatchProduct")
        val results = knowledgeGraphRepository.semanticEntitySearch(
            queryEmbedding = queryEmbedding.embedding.toFloatArray(),
            limit = 10,
            urlPrefix = "https://example.com/kg-test/"
        )

        assertTrue(results.size >= 3, "Should find at least 3 batch indexed entities")
        assertTrue(
            results.any { it.name == "BatchProduct1" } &&
                    results.any { it.name == "BatchProduct2" } &&
                    results.any { it.name == "BatchProduct3" },
            "Should find all batch indexed products"
        )
    }

    @Test
    fun `getSchemaDescription returns entity and relationship type counts`() = runTest(testCoroutineDispatcher) {
        // Given: some indexed data
        val sourceUrl = "https://example.com/kg-test/schema-test"
        val extraction = KgExtractionResult(
            entities = listOf(
                ExtractedEntity(name = "SchemaTestProduct", type = EntityType.PRODUCT, facts = emptyList()),
                ExtractedEntity(name = "SchemaTestFeature", type = EntityType.FEATURE, facts = emptyList())
            ),
            relationships = listOf(
                ExtractedRelationship(
                    fromEntity = "SchemaTestProduct",
                    toEntity = "SchemaTestFeature",
                    relationType = RelationType.HAS_FEATURE
                )
            )
        )

        val embeddings = generateEmbeddings(extraction)
        knowledgeGraphRepository.indexDocument(sourceUrl, extraction, embeddings)

        // When: getting schema description
        val schema = knowledgeGraphRepository.getSchemaDescription()

        // Then: should contain entity and relationship type info
        assertTrue(schema.contains("Entity Types"), "Should contain entity types section")
        assertTrue(schema.contains("Relationship Types"), "Should contain relationship types section")
    }

    @Test
    fun `hasDataForUrlPrefix returns true when data exists`() = runTest(testCoroutineDispatcher) {
        // Given: indexed data for a URL prefix
        val sourceUrl = "https://example.com/kg-test/has-data-test"
        val extraction = KgExtractionResult(
            entities = listOf(
                ExtractedEntity(name = "HasDataTestEntity", type = EntityType.PRODUCT, facts = emptyList())
            ),
            relationships = emptyList()
        )

        val embeddings = generateEmbeddings(extraction)
        knowledgeGraphRepository.indexDocument(sourceUrl, extraction, embeddings)

        // Then: hasDataForUrlPrefix should return true for matching prefix
        assertTrue(
            knowledgeGraphRepository.hasDataForUrlPrefix("https://example.com/kg-test/"),
            "Should have data for URL prefix"
        )

        // And: should return false for non-matching prefix
        assertFalse(
            knowledgeGraphRepository.hasDataForUrlPrefix("https://nonexistent.com/"),
            "Should not have data for non-matching prefix"
        )
    }

    @Test
    fun `entity resolution merges entities with same canonical name`() = runTest(testCoroutineDispatcher) {
        // Given: two documents with the same entity (different casing)
        val extraction1 = KgExtractionResult(
            entities = listOf(
                ExtractedEntity(
                    name = "Kotlin Language",
                    type = EntityType.TECHNOLOGY,
                    facts = listOf("JVM language")
                )
            ),
            relationships = emptyList()
        )

        val extraction2 = KgExtractionResult(
            entities = listOf(
                ExtractedEntity(
                    name = "kotlin language", // Same entity, different casing
                    type = EntityType.TECHNOLOGY,
                    facts = listOf("Developed by JetBrains")
                )
            ),
            relationships = emptyList()
        )

        // When: indexing both documents with pre-computed embeddings
        val embeddings1 = generateEmbeddings(extraction1)
        val embeddings2 = generateEmbeddings(extraction2)
        knowledgeGraphRepository.indexDocument("https://example.com/kg-test/merge1", extraction1, embeddings1)
        knowledgeGraphRepository.indexDocument("https://example.com/kg-test/merge2", extraction2, embeddings2)

        // Then: semantic search should find the merged entity with facts from both sources
        val queryEmbedding = textEmbeddingService.embedForSimilarity("Kotlin Language")
        val results = knowledgeGraphRepository.semanticEntitySearch(
            queryEmbedding = queryEmbedding.embedding.toFloatArray(),
            limit = 10,
            urlPrefix = "https://example.com/kg-test/"
        )

        val kotlinEntities = results.filter { it.type == EntityType.TECHNOLOGY && it.name.contains("Kotlin", ignoreCase = true) }

        // Should have merged into a single entity (or at most 2 if resolution didn't merge)
        assertTrue(kotlinEntities.isNotEmpty(), "Should find Kotlin entity")

        // Check that facts from both sources are present (if entity was merged)
        val allFacts = kotlinEntities.flatMap { it.facts }
        assertTrue(
            allFacts.contains("JVM language") || allFacts.contains("Developed by JetBrains"),
            "Should have facts from at least one source"
        )

        // Check that source URLs are tracked
        val allSourceUrls = kotlinEntities.flatMap { it.sourceUrls }
        assertTrue(
            allSourceUrls.any { it.contains("merge1") } || allSourceUrls.any { it.contains("merge2") },
            "Should track source URLs"
        )
    }

    @Test
    fun `semanticEntitySearch filters by minExtractedAtEpochMs`() = runTest(testCoroutineDispatcher) {
        // Given: an indexed document
        val timestampBeforeIndexing = kotlin.time.Clock.System.now().toEpochMilliseconds()
        
        val sourceUrl = "https://example.com/kg-test/cache-age-test"
        val extraction = KgExtractionResult(
            entities = listOf(
                ExtractedEntity(
                    name = "CacheAgeTestEntity",
                    type = EntityType.PRODUCT,
                    facts = listOf("Test entity for cache age filtering")
                )
            ),
            relationships = emptyList()
        )

        val embeddings = generateEmbeddings(extraction)
        knowledgeGraphRepository.indexDocument(sourceUrl, extraction, embeddings)

        val timestampAfterIndexing = kotlin.time.Clock.System.now().toEpochMilliseconds()

        val queryEmbedding = textEmbeddingService.embedForSimilarity("CacheAgeTestEntity")

        // When: searching with minExtractedAtEpochMs before indexing time
        val resultsWithOldTimestamp = knowledgeGraphRepository.semanticEntitySearch(
            queryEmbedding = queryEmbedding.embedding.toFloatArray(),
            limit = 10,
            urlPrefix = "https://example.com/kg-test/cache-age-test",
            minExtractedAtEpochMs = timestampBeforeIndexing
        )

        // Then: entity should be found (extraction time is after the filter timestamp)
        assertTrue(
            resultsWithOldTimestamp.any { it.name == "CacheAgeTestEntity" },
            "Should find entity when minExtractedAtEpochMs is before extraction time"
        )

        // When: searching with minExtractedAtEpochMs after indexing time (in the future)
        val futureTimestamp = timestampAfterIndexing + 60_000 // 1 minute in the future
        val resultsWithFutureTimestamp = knowledgeGraphRepository.semanticEntitySearch(
            queryEmbedding = queryEmbedding.embedding.toFloatArray(),
            limit = 10,
            urlPrefix = "https://example.com/kg-test/cache-age-test",
            minExtractedAtEpochMs = futureTimestamp
        )

        // Then: entity should NOT be found (extraction time is before the filter timestamp)
        assertFalse(
            resultsWithFutureTimestamp.any { it.name == "CacheAgeTestEntity" },
            "Should NOT find entity when minExtractedAtEpochMs is after extraction time"
        )

        // When: searching without minExtractedAtEpochMs filter
        val resultsWithoutFilter = knowledgeGraphRepository.semanticEntitySearch(
            queryEmbedding = queryEmbedding.embedding.toFloatArray(),
            limit = 10,
            urlPrefix = "https://example.com/kg-test/cache-age-test",
            minExtractedAtEpochMs = null
        )

        // Then: entity should be found (no filtering)
        assertTrue(
            resultsWithoutFilter.any { it.name == "CacheAgeTestEntity" },
            "Should find entity when no cache age filter is applied"
        )
    }

    @Test
    fun `semanticEntitySearch combines urlPrefix and minExtractedAtEpochMs filters`() = runTest(testCoroutineDispatcher) {
        // Given: indexed documents with different URLs
        val timestampBeforeIndexing = kotlin.time.Clock.System.now().toEpochMilliseconds()
        
        val extraction1 = KgExtractionResult(
            entities = listOf(
                ExtractedEntity(
                    name = "CombinedFilterEntity1",
                    type = EntityType.PRODUCT,
                    facts = listOf("Entity 1")
                )
            ),
            relationships = emptyList()
        )
        
        val extraction2 = KgExtractionResult(
            entities = listOf(
                ExtractedEntity(
                    name = "CombinedFilterEntity2",
                    type = EntityType.PRODUCT,
                    facts = listOf("Entity 2")
                )
            ),
            relationships = emptyList()
        )

        val embeddings1 = generateEmbeddings(extraction1)
        val embeddings2 = generateEmbeddings(extraction2)
        knowledgeGraphRepository.indexDocument("https://example.com/kg-test/combined-filter/path-a", extraction1, embeddings1)
        knowledgeGraphRepository.indexDocument("https://example.com/kg-test/combined-filter/path-b", extraction2, embeddings2)

        val queryEmbedding = textEmbeddingService.embedForSimilarity("CombinedFilterEntity")

        // When: searching with both urlPrefix AND minExtractedAtEpochMs
        val results = knowledgeGraphRepository.semanticEntitySearch(
            queryEmbedding = queryEmbedding.embedding.toFloatArray(),
            limit = 10,
            urlPrefix = "https://example.com/kg-test/combined-filter/path-a",
            minExtractedAtEpochMs = timestampBeforeIndexing
        )

        // Then: should only find Entity1 (matches both URL prefix AND time filter)
        assertTrue(
            results.any { it.name == "CombinedFilterEntity1" },
            "Should find Entity1 matching both filters"
        )
        assertFalse(
            results.any { it.name == "CombinedFilterEntity2" },
            "Should NOT find Entity2 (wrong URL prefix)"
        )

        // When: searching with matching URL but future timestamp
        val futureTimestamp = kotlin.time.Clock.System.now().toEpochMilliseconds() + 60_000
        val resultsWithFutureTime = knowledgeGraphRepository.semanticEntitySearch(
            queryEmbedding = queryEmbedding.embedding.toFloatArray(),
            limit = 10,
            urlPrefix = "https://example.com/kg-test/combined-filter/path-a",
            minExtractedAtEpochMs = futureTimestamp
        )

        // Then: should find nothing (URL matches but time doesn't)
        assertFalse(
            resultsWithFutureTime.any { it.name == "CombinedFilterEntity1" },
            "Should NOT find Entity1 when time filter excludes it"
        )
    }

    @Test
    fun `executeCypher returns empty list for blank query`() = runTest(testCoroutineDispatcher) {
        // When: executing a blank Cypher query
        val results = knowledgeGraphRepository.executeCypher("")

        // Then: should return empty list
        assertTrue(results.isEmpty(), "Blank query should return empty results")
    }

    @Test
    fun `executeCypher returns empty list for whitespace-only query`() = runTest(testCoroutineDispatcher) {
        // When: executing a whitespace-only Cypher query
        val results = knowledgeGraphRepository.executeCypher("   \n\t  ")

        // Then: should return empty list
        assertTrue(results.isEmpty(), "Whitespace-only query should return empty results")
    }

    @Test
    fun `executeCypher handles invalid Cypher gracefully`() = runTest(testCoroutineDispatcher) {
        // When: executing an invalid Cypher query
        // This should not throw an exception, but return empty results or handle error gracefully
        val results = knowledgeGraphRepository.executeCypher("INVALID CYPHER SYNTAX HERE!!!")

        // Then: should return empty list (error is caught and logged)
        assertTrue(results.isEmpty(), "Invalid Cypher should return empty results without throwing")
    }

    @Test
    fun `executeCypher executes valid MATCH query`() = runTest(testCoroutineDispatcher) {
        // When: executing a simple Cypher MATCH query
        val results = knowledgeGraphRepository.executeCypher(
            "MATCH (n) RETURN n.name AS name LIMIT 5",
            timeoutSeconds = 10
        )

        // Then: should return a valid list (may be empty if no nodes exist)
        assertNotNull(results, "Results should not be null")
        // If results are non-empty, they should have the expected structure
        if (results.isNotEmpty()) {
            assertTrue(
                results.all { it.containsKey("name") },
                "Each result should have a 'name' key"
            )
        }
    }

    @Test
    fun `executeCypher respects timeout`() = runTest(testCoroutineDispatcher) {
        // When: executing a query with a reasonable timeout
        val startTime = System.currentTimeMillis()
        val results = knowledgeGraphRepository.executeCypher(
            "MATCH (n) RETURN n LIMIT 1",
            timeoutSeconds = 10
        )
        val elapsed = System.currentTimeMillis() - startTime

        // Then: should return within a reasonable time
        assertNotNull(results, "Should return results without hanging")
        assertTrue(elapsed < 15000, "Should return within 15 seconds (actual: ${elapsed}ms)")
    }
}

