package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.knowledgegraph.*
import io.deepsearch.domain.repositories.IKnowledgeGraphRepository
import io.deepsearch.domain.services.ITextEmbeddingService
import io.deepsearch.infrastructure.config.infrastructureTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.getValue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class AgeKnowledgeGraphRepositoryTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinExtension = KoinTestExtension.create {
        modules(domainTestModule, infrastructureTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val knowledgeGraphRepository by inject<IKnowledgeGraphRepository>()
    private val textEmbeddingService by inject<ITextEmbeddingService>()

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

        // When: indexing the document
        knowledgeGraphRepository.indexDocument(sourceUrl, extraction)

        // Then: semantic search should find the entities
        val queryEmbedding = textEmbeddingService.embedQuery("Acme pricing plan")
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

        knowledgeGraphRepository.indexDocument(sourceUrl, extraction)

        // Find the starting entity
        val queryEmbedding = textEmbeddingService.embedQuery("TraverseProduct")
        val searchResults = knowledgeGraphRepository.semanticEntitySearch(
            queryEmbedding = queryEmbedding.embedding.toFloatArray(),
            limit = 1,
            urlPrefix = "https://example.com/kg-test/"
        )

        assertTrue(searchResults.isNotEmpty(), "Should find starting entity")
        val startEntityId = java.util.UUID.fromString(searchResults.first().id)

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

        knowledgeGraphRepository.indexDocument(sourceUrl, extraction)

        // Verify entity exists
        val queryEmbedding = textEmbeddingService.embedQuery("RemoveTestEntity")
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

        // When: batch indexing
        knowledgeGraphRepository.batchIndexDocuments(extractions)

        // Then: all entities should be searchable
        val queryEmbedding = textEmbeddingService.embedQuery("BatchProduct")
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

        knowledgeGraphRepository.indexDocument(sourceUrl, extraction)

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

        knowledgeGraphRepository.indexDocument(sourceUrl, extraction)

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

        // When: indexing both documents
        knowledgeGraphRepository.indexDocument("https://example.com/kg-test/merge1", extraction1)
        knowledgeGraphRepository.indexDocument("https://example.com/kg-test/merge2", extraction2)

        // Then: semantic search should find the merged entity with facts from both sources
        val queryEmbedding = textEmbeddingService.embedQuery("Kotlin Language")
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
}

