package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ITextToCypherAgent
import io.deepsearch.domain.agents.TextToCypherInput
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.core.component.inject
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TextToCypherAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<ITextToCypherAgent>()

    private val sampleSchemaDescription = """
        Entity Types:
          - PRODUCT: 15 entities
          - PRICING_TIER: 8 entities
          - FEATURE: 42 entities
          - PRICE: 12 entities
          - TECHNOLOGY: 23 entities
          - COMPANY: 5 entities
        
        Relationship Types:
          - HAS_FEATURE: 67 relationships
          - HAS_PRICE: 20 relationships
          - INCLUDES: 15 relationships
          - INTEGRATES_WITH: 18 relationships
          - OWNED_BY: 5 relationships
    """.trimIndent()

    @Test
    fun `generates valid Cypher for simple entity lookup`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TextToCypherInput(
                query = "What products are available?",
                schemaDescription = sampleSchemaDescription
            )
        )

        assertNotNull(output.cypherQuery, "Should generate a Cypher query")
        assertTrue(output.cypherQuery.isNotBlank(), "Cypher query should not be blank")
        assertTrue(
            output.cypherQuery.contains("MATCH", ignoreCase = true) ||
            output.cypherQuery.contains("RETURN", ignoreCase = true),
            "Should contain Cypher keywords"
        )
        assertNotNull(output.explanation, "Should provide an explanation")
    }

    @Test
    fun `generates Cypher for relationship traversal`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TextToCypherInput(
                query = "What features does the Pro Plan include?",
                schemaDescription = sampleSchemaDescription
            )
        )

        assertNotNull(output.cypherQuery, "Should generate a Cypher query")
        assertTrue(
            output.cypherQuery.contains("HAS_FEATURE", ignoreCase = true) ||
            output.cypherQuery.contains("INCLUDES", ignoreCase = true) ||
            output.cypherQuery.contains("Pro Plan", ignoreCase = true) ||
            output.cypherQuery.contains("->", ignoreCase = true) ||
            output.cypherQuery.contains("-[", ignoreCase = true),
            "Should reference features or relationships in query"
        )
    }

    @Test
    fun `generates Cypher for price queries`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TextToCypherInput(
                query = "What is the price of the Enterprise tier?",
                schemaDescription = sampleSchemaDescription
            )
        )

        assertNotNull(output.cypherQuery, "Should generate a Cypher query")
        assertTrue(
            output.cypherQuery.contains("PRICE", ignoreCase = true) ||
            output.cypherQuery.contains("HAS_PRICE", ignoreCase = true) ||
            output.cypherQuery.contains("Enterprise", ignoreCase = true),
            "Should reference price entities or relationships"
        )
    }

    @Test
    fun `generates Cypher for technology integration queries`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TextToCypherInput(
                query = "What technologies does the product integrate with?",
                schemaDescription = sampleSchemaDescription
            )
        )

        assertNotNull(output.cypherQuery, "Should generate a Cypher query")
        assertTrue(
            output.cypherQuery.contains("TECHNOLOGY", ignoreCase = true) ||
            output.cypherQuery.contains("INTEGRATES_WITH", ignoreCase = true),
            "Should reference technology entities or integration relationships"
        )
    }

    @Test
    fun `handles complex multi-hop queries`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TextToCypherInput(
                query = "Find all features of products owned by Acme Corp",
                schemaDescription = sampleSchemaDescription
            )
        )

        assertNotNull(output.cypherQuery, "Should generate a Cypher query")
        // Multi-hop queries should have multiple relationship patterns
        assertTrue(
            output.cypherQuery.contains("MATCH", ignoreCase = true),
            "Should contain MATCH clause"
        )
        assertTrue(
            output.cypherQuery.contains("RETURN", ignoreCase = true),
            "Should contain RETURN clause"
        )
    }

    @Test
    fun `provides explanation for generated query`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TextToCypherInput(
                query = "List all pricing tiers with their features",
                schemaDescription = sampleSchemaDescription
            )
        )

        assertNotNull(output.explanation, "Should provide an explanation")
        assertTrue(
            output.explanation.isNotBlank(),
            "Explanation should not be blank"
        )
    }

    @Test
    fun `marks query validity appropriately`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TextToCypherInput(
                query = "What products exist?",
                schemaDescription = sampleSchemaDescription
            )
        )

        // For a simple valid query, isValid should be true
        assertTrue(
            output.isValid,
            "Simple product lookup query should be marked as valid"
        )
    }

    @Test
    fun `handles empty schema gracefully`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TextToCypherInput(
                query = "What products are available?",
                schemaDescription = "No entities or relationships in the knowledge graph."
            )
        )

        // Should still generate something or indicate it can't
        assertNotNull(output.cypherQuery, "Should return a response")
        assertNotNull(output.explanation, "Should provide an explanation")
    }

    @Test
    fun `generates AGE-compatible Cypher syntax`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TextToCypherInput(
                query = "Find products with price less than 100 dollars",
                schemaDescription = sampleSchemaDescription
            )
        )

        assertNotNull(output.cypherQuery, "Should generate a Cypher query")
        
        // AGE-compatible queries should use standard Cypher syntax
        val query = output.cypherQuery.uppercase()
        val hasValidSyntax = query.contains("MATCH") || 
                             query.contains("RETURN") ||
                             query.contains("WHERE")
        
        assertTrue(
            hasValidSyntax || !output.isValid,
            "Should use valid Cypher syntax or mark as invalid"
        )
    }

    @Test
    fun `returns token usage metrics`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TextToCypherInput(
                query = "What products are available?",
                schemaDescription = sampleSchemaDescription
            )
        )

        assertTrue(
            output.tokenUsage.totalTokens > 0,
            "Should return token usage metrics"
        )
    }

    @Test
    fun `handles queries with specific entity names`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TextToCypherInput(
                query = "What features does DataEngine include?",
                schemaDescription = sampleSchemaDescription
            )
        )

        assertNotNull(output.cypherQuery, "Should generate a Cypher query")
        assertTrue(
            output.cypherQuery.contains("DataEngine", ignoreCase = true) ||
            output.cypherQuery.contains("name", ignoreCase = true),
            "Should reference the specific entity name or use name property"
        )
    }

    @Test
    fun `handles comparison queries`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TextToCypherInput(
                query = "Compare the features of Pro Plan and Enterprise Plan",
                schemaDescription = sampleSchemaDescription
            )
        )

        assertNotNull(output.cypherQuery, "Should generate a Cypher query")
        assertTrue(
            output.cypherQuery.contains("Pro", ignoreCase = true) ||
            output.cypherQuery.contains("Enterprise", ignoreCase = true) ||
            output.cypherQuery.contains("PRICING_TIER", ignoreCase = true),
            "Should reference the plans being compared"
        )
    }
}

