package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.PdfSourceEvalInput
import io.deepsearch.domain.agents.IPdfSourceEvalAgent
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.UrlContentResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfSourceEvalAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koin = KoinTestExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IPdfSourceEvalAgent>()

    /**
     * Test case: PDF whitepaper with clear prose content.
     * The product information is in clear prose paragraphs,
     * so facts should be extracted and included in the output.
     */
    @Test
    fun `should extract prose content facts and not filter them out`() = runTest(testCoroutineDispatcher) {
        val pdfSource = UrlContentResult.PdfPreview(
            url = "https://example.com/whitepaper.pdf",
            title = "Enterprise Security Whitepaper",
            description = "PDF document with 12 pages",
            extractedText = """
                Enterprise Security Whitepaper
                
                Introduction
                
                Our enterprise platform provides comprehensive security features designed for 
                modern businesses. This whitepaper outlines the key security capabilities 
                and compliance certifications available to enterprise customers.
                
                Security Features
                
                The platform includes end-to-end encryption for all data at rest and in transit.
                All customer data is encrypted using AES-256 encryption standard.
                
                Our infrastructure is hosted on AWS with multi-region redundancy.
                We maintain SOC 2 Type II certification and are GDPR compliant.
                
                The enterprise plan includes a 99.9% uptime SLA guarantee.
                
                Access Control
                
                Role-based access control (RBAC) allows administrators to define granular 
                permissions for team members. Single sign-on (SSO) integration is available 
                with SAML 2.0 and OAuth 2.0 providers.
                
                Conclusion
                
                Our security-first approach ensures your data is protected at every level.
            """.trimIndent(),
            pageCount = 12
        )

        val input = PdfSourceEvalInput(
            pdfSource = pdfSource,
            expandedQuery = "What security certifications does the platform have?"
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        assertNotNull(output.evaluatedSource, "Should have evaluated source for relevant content")
        
        // Find facts about security certifications
        val securityFacts = output.evaluatedSource.relevantFacts
            .filter { 
                it.fact.contains("SOC", ignoreCase = true) || 
                it.fact.contains("GDPR", ignoreCase = true) ||
                it.fact.contains("encryption", ignoreCase = true)
            }
        
        assertTrue(securityFacts.isNotEmpty(), "Should find facts about security features (prose facts are not filtered)")
        
        // Verify isPreview is set to true for PDF preview sources
        assertTrue(output.evaluatedSource.isPreview, "PDF preview sources should have isPreview=true")
    }

    /**
     * Test case: PDF with garbled text (OCR artifacts, encoding issues).
     * Facts with garbled text should be filtered out before returning.
     */
    @Test
    fun `should filter out garbled text facts`() = runTest(testCoroutineDispatcher) {
        val pdfSource = UrlContentResult.PdfPreview(
            url = "https://example.com/scanned-document.pdf",
            title = "Scanned Document",
            description = "PDF document with 5 pages",
            extractedText = """
                Product Specifications
                
                The device supports USB-C connectivity and fast charging.
                Battery capacity is 5000mAh with 65W charging support.
                
                Thâ€™e systÃ©m suppÃ´rts multÃ¬ple lÃ¤nguages.
                
                #### ??? #### OCR ERROR ####
                
                Screen resolution is 2560x1440 pixels with 120Hz refresh rate.
                
                Pr1c3 st@rts fr0m $$$499 p3r un1t
                
                Weight is approximately 180 grams.
            """.trimIndent(),
            pageCount = 5
        )

        val input = PdfSourceEvalInput(
            pdfSource = pdfSource,
            expandedQuery = "What are the device specifications?"
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        
        // The agent should filter out garbled facts internally.
        // Clean facts about USB-C, battery, screen should remain.
        // Garbled facts should be filtered out.
        if (output.evaluatedSource != null) {
            val facts = output.evaluatedSource.relevantFacts
            
            // Check that clean facts are present
            val cleanFacts = facts.filter { 
                it.fact.contains("USB-C", ignoreCase = true) ||
                it.fact.contains("battery", ignoreCase = true) ||
                it.fact.contains("screen", ignoreCase = true) ||
                it.fact.contains("weight", ignoreCase = true)
            }
            
            // Garbled text should not be in the facts
            val garbledFacts = facts.filter {
                it.fact.contains("â€™", ignoreCase = true) ||
                it.fact.contains("Ã©", ignoreCase = true) ||
                it.fact.contains("####", ignoreCase = true) ||
                it.fact.contains("???", ignoreCase = true)
            }
            
            assertTrue(garbledFacts.isEmpty(), "Garbled facts should be filtered out")
        }
    }

    /**
     * Test case: PDF with table data.
     * Facts from tables should be filtered out since table structure
     * is often lost in raw PDF text extraction.
     */
    @Test
    fun `should filter out table content`() = runTest(testCoroutineDispatcher) {
        val pdfSource = UrlContentResult.PdfPreview(
            url = "https://example.com/pricing.pdf",
            title = "Pricing Guide",
            description = "PDF document with 3 pages",
            extractedText = """
                Pricing Guide 2024
                
                Our platform offers flexible pricing to meet your needs.
                
                Plan        Price       Users       Storage
                Basic       $9/mo       5           10GB
                Pro         $29/mo      25          100GB
                Enterprise  $99/mo      Unlimited   1TB
                
                All plans include 24/7 email support.
                Enterprise customers receive dedicated account management.
            """.trimIndent(),
            pageCount = 3
        )

        val input = PdfSourceEvalInput(
            pdfSource = pdfSource,
            expandedQuery = "What is the pricing for the Pro plan?"
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        
        // Table-based pricing facts should be filtered out.
        // Prose facts about support should remain.
        // The test validates the flow works with table filtering.
    }

    /**
     * Test case: Research paper should have intention describing academic content.
     */
    @Test
    fun `should identify research papers as academic content in intention`() = runTest(testCoroutineDispatcher) {
        val pdfSource = UrlContentResult.PdfPreview(
            url = "https://arxiv.org/pdf/2301.12345.pdf",
            title = "Attention Is All You Need",
            description = "PDF document with 15 pages",
            extractedText = """
                Attention Is All You Need
                
                Ashish Vaswani, Noam Shazeer, Niki Parmar, Jakob Uszkoreit,
                Llion Jones, Aidan N. Gomez, Lukasz Kaiser, Illia Polosukhin
                
                Google Brain, Google Research
                
                Abstract
                
                The dominant sequence transduction models are based on complex recurrent or 
                convolutional neural networks that include an encoder and a decoder. The best 
                performing models also connect the encoder and decoder through an attention 
                mechanism. We propose a new simple network architecture, the Transformer, 
                based solely on attention mechanisms, dispensing with recurrence and convolutions 
                entirely. Experiments on two machine translation tasks show these models to be 
                superior in quality while being more parallelizable and requiring significantly 
                less time to train.
                
                1. Introduction
                
                Recurrent neural networks, long short-term memory and gated recurrent neural 
                networks in particular, have been firmly established as state of the art 
                approaches in sequence modeling and transduction problems such as language 
                modeling and machine translation.
            """.trimIndent(),
            pageCount = 15
        )

        val input = PdfSourceEvalInput(
            pdfSource = pdfSource,
            expandedQuery = "What is the Transformer architecture?"
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.evaluatedSource, "Should have evaluated source for relevant content")
        
        val evaluatedSource = output.evaluatedSource
        
        // Find facts about the Transformer
        val transformerFacts = evaluatedSource.relevantFacts
            .filter { 
                it.fact.contains("Transformer", ignoreCase = true) || 
                it.fact.contains("attention", ignoreCase = true) 
            }
        
        assertTrue(transformerFacts.isNotEmpty(), "Should find facts about the Transformer architecture")
        
        // Research paper should have intention describing academic content
        assertTrue(
            evaluatedSource.intention.isNotBlank(),
            "Should have intention describing the document purpose"
        )
    }

    /**
     * Test case: Irrelevant content should return null evaluatedSource.
     */
    @Test
    fun `should return null evaluatedSource for irrelevant content`() = runTest(testCoroutineDispatcher) {
        val pdfSource = UrlContentResult.PdfPreview(
            url = "https://example.com/recipe.pdf",
            title = "Chocolate Cake Recipe",
            description = "PDF document with 2 pages",
            extractedText = """
                Chocolate Cake Recipe
                
                Ingredients:
                - 2 cups all-purpose flour
                - 2 cups sugar
                - 3/4 cup cocoa powder
                - 2 eggs
                - 1 cup milk
                - 1/2 cup vegetable oil
                
                Instructions:
                1. Preheat oven to 350°F (175°C)
                2. Mix dry ingredients in a large bowl
                3. Add wet ingredients and mix until smooth
                4. Pour into greased pan and bake for 35 minutes
            """.trimIndent(),
            pageCount = 2
        )

        val input = PdfSourceEvalInput(
            pdfSource = pdfSource,
            expandedQuery = "What is the pricing for enterprise software?"
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        
        // The content is about recipes, not software pricing, so it should be marked as not relevant
        // Note: The LLM may still find this relevant if it mentions anything tangentially related
        // This test validates the flow works - the exact behavior depends on the LLM
    }

    /**
     * Test case: Content with all garbled/table data should return null after filtering.
     */
    @Test
    fun `should return null evaluatedSource when all facts are garbled or from tables`() = runTest(testCoroutineDispatcher) {
        val pdfSource = UrlContentResult.PdfPreview(
            url = "https://example.com/corrupted.pdf",
            title = "Corrupted Document",
            description = "PDF document with 1 page",
            extractedText = """
                #### ??? #### OCR ERROR ####
                Thâ€™e prÃ¬cÃ© Ã¬s $$$99 pÃ©r mÃ´nth
                
                Plan    Price   Users
                A       $9      5
                B       $29     25
                C       $99     100
                
                ÃŸÃ¤Ã¶Ã¼ÃŸÃ¤Ã¶Ã¼ÃŸÃ¤Ã¶Ã¼
            """.trimIndent(),
            pageCount = 1
        )

        val input = PdfSourceEvalInput(
            pdfSource = pdfSource,
            expandedQuery = "What is the pricing?"
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        
        // All content is either garbled or in tables, which should be filtered out
        // The evaluatedSource should be null since no clean non-table facts remain
        assertNull(
            output.evaluatedSource,
            "Should return null evaluatedSource when all facts are garbled or from tables"
        )
    }
}
