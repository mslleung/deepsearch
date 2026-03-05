package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.EntityExtractionInput
import io.deepsearch.domain.agents.IEntityExtractionAgent
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.knowledgegraph.EntityType
import io.deepsearch.domain.knowledgegraph.RelationType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.core.component.inject
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EntityExtractionAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IEntityExtractionAgent>()

    @Test
    fun `empty markdown returns empty extraction`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            EntityExtractionInput(
                markdown = "",
                sourceUrl = "https://example.com/empty"
            )
        )
        
        assertTrue(output.extraction.isEmpty(), "Empty markdown should return empty extraction")
    }

    @Test
    fun `extracts product entities from pricing page markdown`() = runTest(testCoroutineDispatcher) {
        val markdown = """
            # Pricing Plans
            
            ## Pro Plan - ${'$'}99/month
            
            The Pro Plan is our most popular option for growing businesses.
            
            Features included:
            - Advanced Analytics Dashboard
            - Unlimited API Requests
            - Priority Support
            - Custom Integrations
            
            ## Enterprise Plan - Contact Sales
            
            For large organizations with custom needs.
            
            Everything in Pro, plus:
            - Dedicated Account Manager
            - SSO Integration
            - SLA Guarantee
        """.trimIndent()

        val output = agent.generate(
            EntityExtractionInput(
                markdown = markdown,
                sourceUrl = "https://example.com/pricing"
            )
        )

        assertNotNull(output.extraction, "Extraction should not be null")
        assertTrue(output.extraction.isNotEmpty(), "Should extract entities from pricing content")
        
        // Should extract pricing tier entities
        val pricingTiers = output.extraction.entities.filter { it.type == EntityType.PRICING_TIER }
        assertTrue(
            pricingTiers.isNotEmpty() || output.extraction.entities.any { it.type == EntityType.PRODUCT },
            "Should extract pricing tier or product entities"
        )
        
        // Should extract price entities
        val priceEntities = output.extraction.entities.filter { it.type == EntityType.PRICE }
        assertTrue(
            priceEntities.any { it.name.contains("99") || it.name.contains("${'$'}") } ||
            output.extraction.entities.any { it.facts.any { fact -> fact.contains("99") } },
            "Should extract price information"
        )
        
        // Should extract feature entities
        val featureEntities = output.extraction.entities.filter { it.type == EntityType.FEATURE }
        assertTrue(
            featureEntities.isNotEmpty() || 
            output.extraction.entities.any { it.facts.any { fact -> 
                fact.contains("Analytics") || fact.contains("API") 
            }},
            "Should extract feature information"
        )
    }

    @Test
    fun `extracts relationships between entities`() = runTest(testCoroutineDispatcher) {
        val markdown = """
            # Acme Software
            
            Acme Software is a product developed by Acme Inc.
            
            ## Features
            
            - Real-time Collaboration: Work together seamlessly
            - Cloud Storage: 100GB included
            
            ## Pricing
            
            Starter Plan: ${'$'}29/month
            - Includes Real-time Collaboration
            - 10GB Cloud Storage
        """.trimIndent()

        val output = agent.generate(
            EntityExtractionInput(
                markdown = markdown,
                sourceUrl = "https://acme.com/product"
            )
        )

        assertTrue(output.extraction.isNotEmpty(), "Should extract entities")
        
        // Should have relationships
        val hasRelationships = output.extraction.relationships.isNotEmpty()
        val hasHasFeature = output.extraction.relationships.any { it.relationType == RelationType.HAS_FEATURE }
        val hasHasPrice = output.extraction.relationships.any { it.relationType == RelationType.HAS_PRICE }
        val hasIncludes = output.extraction.relationships.any { it.relationType == RelationType.INCLUDES }
        
        assertTrue(
            hasRelationships && (hasHasFeature || hasHasPrice || hasIncludes),
            "Should extract relationships between entities (found ${output.extraction.relationships.size} relationships)"
        )
    }

    @Test
    fun `extracts technology entities from documentation`() = runTest(testCoroutineDispatcher) {
        val markdown = """
            # Integration Guide
            
            Our platform integrates with the following technologies:
            
            - **Kubernetes**: Deploy using Helm charts
            - **PostgreSQL**: Primary database support
            - **Redis**: Caching layer
            - **React**: Frontend framework
            
            ## API Authentication
            
            We use OAuth 2.0 for authentication with JWT tokens.
        """.trimIndent()

        val output = agent.generate(
            EntityExtractionInput(
                markdown = markdown,
                sourceUrl = "https://docs.example.com/integration"
            )
        )

        assertTrue(output.extraction.isNotEmpty(), "Should extract entities from technical documentation")
        
        val techEntities = output.extraction.entities.filter { it.type == EntityType.TECHNOLOGY }
        assertTrue(
            techEntities.isNotEmpty() || output.extraction.entities.any { 
                it.name.contains("Kubernetes", ignoreCase = true) ||
                it.name.contains("PostgreSQL", ignoreCase = true) ||
                it.name.contains("React", ignoreCase = true)
            },
            "Should extract technology entities"
        )
    }

    @Test
    fun `extracts facts about entities`() = runTest(testCoroutineDispatcher) {
        val markdown = """
            # About DataCorp
            
            DataCorp is a leading data analytics company founded in 2015.
            
            Our flagship product, DataEngine, processes over 1 million queries per second.
            
            Key features:
            - Real-time processing
            - 99.99% uptime SLA
            - GDPR compliant
        """.trimIndent()

        val output = agent.generate(
            EntityExtractionInput(
                markdown = markdown,
                sourceUrl = "https://datacorp.com/about"
            )
        )

        assertTrue(output.extraction.isNotEmpty(), "Should extract entities")
        
        // Check that entities have facts
        val entitiesWithFacts = output.extraction.entities.filter { it.facts.isNotEmpty() }
        assertTrue(
            entitiesWithFacts.isNotEmpty(),
            "Should extract facts about entities"
        )
        
        // Check for specific facts
        val allFacts = output.extraction.entities.flatMap { it.facts }
        assertTrue(
            allFacts.any { 
                it.contains("2015") || 
                it.contains("million") || 
                it.contains("uptime") ||
                it.contains("analytics")
            },
            "Should extract meaningful facts from content"
        )
    }

    @Test
    fun `handles markdown with multiple sections`() = runTest(testCoroutineDispatcher) {
        val markdown = """
            # Product Overview
            
            ## Core Product
            
            MainApp is our core offering at ${'$'}49/month.
            
            ## Add-ons
            
            ### Analytics Add-on - ${'$'}19/month
            Advanced reporting and insights.
            
            ### Storage Add-on - ${'$'}9/month  
            Additional 50GB storage.
            
            ## Competitors
            
            We compete with BigCorp and SmallTech in the market.
        """.trimIndent()

        val output = agent.generate(
            EntityExtractionInput(
                markdown = markdown,
                sourceUrl = "https://example.com/overview"
            )
        )

        assertTrue(output.extraction.isNotEmpty(), "Should extract entities from multi-section content")
        assertTrue(
            output.extraction.entities.size >= 3,
            "Should extract multiple entities (found ${output.extraction.entities.size})"
        )
    }

    @Test
    fun `returns token usage metrics`() = runTest(testCoroutineDispatcher) {
        val markdown = "Simple content about Product X"

        val output = agent.generate(
            EntityExtractionInput(
                markdown = markdown,
                sourceUrl = "https://example.com/simple"
            )
        )

        assertTrue(
            output.tokenUsage.totalTokens > 0,
            "Should return token usage metrics"
        )
    }
}

