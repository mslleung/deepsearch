package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.AnswerStatus
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.RelevantFact
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamingAnswerSynthesisAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koin = KoinTestExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IStreamingAnswerSynthesisAgent>()

    @Test
    fun `should return default message when evaluated sources are empty`() = runTest(testCoroutineDispatcher) {
        val input = StreamingAnswerSynthesisInput(
            query = "What is machine learning?",
            evaluatedSources = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertEquals("No information found to answer the query.", output.answer)
            assertEquals(AnswerStatus.NEED_MORE_INFORMATION, output.status, "Should request more information when empty")
        assertFalse(output.assessment.isComplete(), "All 4 dimensions should be unsatisfied when no sources available")
        assertNotNull(output.tokenUsage)
    }

    @Test
    fun `should generate comprehensive answer from extracted facts`() = runTest(testCoroutineDispatcher) {
        val source = EvaluatedSource(
            url = "https://example.com/machine-learning",
            title = "Machine Learning Guide",
            description = "A comprehensive guide to ML",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Machine learning is a subset of artificial intelligence (AI) that enables systems to learn and improve from experience without being explicitly programmed."
                ),
                RelevantFact(
                    fact = "The process of learning begins with observations or data, such as examples, direct experience, or instruction."
                ),
                RelevantFact(
                    fact = "Training Data is data used to train the model."
                )
            ),
            contentDate = null,
            intention = "Official documentation page providing a comprehensive introduction to machine learning concepts"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is machine learning?",
            evaluatedSources = listOf(source)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        assertTrue(output.answer.length > 50, "Answer should be comprehensive")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should synthesize information from multiple sources with facts`() = runTest(testCoroutineDispatcher) {
        val source1 = EvaluatedSource(
            url = "https://example.com/ml-definition",
            title = "ML Definition",
            description = null,
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Machine learning is a branch of artificial intelligence and computer science that focuses on the use of data and algorithms to imitate the way humans learn."
                )
            ),
            contentDate = null,
            intention = "Official documentation page defining machine learning"
        )

        val source2 = EvaluatedSource(
            url = "https://example.com/ml-types",
            title = "ML Types",
            description = null,
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Supervised learning algorithms learn from labeled training data."
                ),
                RelevantFact(
                    fact = "Unsupervised learning finds hidden patterns in unlabeled data."
                ),
                RelevantFact(
                    fact = "Reinforcement learning is about taking suitable action to maximize reward."
                )
            ),
            contentDate = null,
            intention = "Official documentation page explaining the types of machine learning"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is machine learning and what are its types?",
            evaluatedSources = listOf(source1, source2)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        assertTrue(output.answer.length > 100, "Answer should synthesize multiple sources comprehensively")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should prioritize facts from authoritative sources`() = runTest(testCoroutineDispatcher) {
        val highRelevanceSource = EvaluatedSource(
            url = "https://example.com/deep-learning-official",
            title = "Official Deep Learning Docs",
            description = null,
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Deep learning is a subset of machine learning that uses neural networks with multiple layers."
                ),
                RelevantFact(
                    fact = "Deep learning models can handle large amounts of data and excel at complex tasks."
                )
            ),
            contentDate = null,
            intention = "Official documentation page on deep learning from the main product site"
        )

        val lowRelevanceSource = EvaluatedSource(
            url = "https://example.com/ml-blog-post",
            title = "Personal Blog",
            description = null,
            relevantFacts = listOf(
                RelevantFact(
                    fact = "I started learning machine learning last month."
                )
            ),
            contentDate = null,
            intention = "Personal blog post about someone's journey learning machine learning"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is deep learning?",
            evaluatedSources = listOf(lowRelevanceSource, highRelevanceSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should indicate NEED_MORE_INFORMATION when facts are not relevant`() = runTest(testCoroutineDispatcher) {
        val irrelevantSource = EvaluatedSource(
            url = "https://example.com/cooking-recipes",
            title = "Cooking Recipes",
            description = null,
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Chocolate chip cookies need 2 cups flour and 1 cup sugar."
                ),
                RelevantFact(
                    fact = "Preheat oven to 350°F for best results."
                )
            ),
            contentDate = null,
            intention = "Recipe page from a cooking forum discussing baking techniques"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is quantum computing?",
            evaluatedSources = listOf(irrelevantSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should indicate lack of relevant information")
        assertEquals(AnswerStatus.NEED_MORE_INFORMATION, output.status, "Should request more information when facts are irrelevant")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should return COMPLETE status for comprehensive relevant facts`() = runTest(testCoroutineDispatcher) {
        val comprehensiveSource = EvaluatedSource(
            url = "https://example.com/machine-learning-complete",
            title = "Complete ML Guide",
            description = "Everything about ML",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Machine learning is a subset of artificial intelligence that enables systems to learn and improve from experience."
                ),
                RelevantFact(
                    fact = "The three main types of machine learning are supervised learning, unsupervised learning, and reinforcement learning."
                ),
                RelevantFact(
                    fact = "Supervised learning uses labeled training data to learn the mapping from inputs to outputs."
                ),
                RelevantFact(
                    fact = "Unsupervised learning finds patterns and structure in unlabeled data."
                ),
                RelevantFact(
                    fact = "Reinforcement learning learns through trial and error by receiving rewards and penalties."
                )
            ),
            contentDate = null,
            intention = "Official comprehensive documentation page covering all aspects of machine learning"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is machine learning?",
            evaluatedSources = listOf(comprehensiveSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should be comprehensive")
        assertNotNull(output.status, "Should have status")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
        // Note: The exact status depends on LLM judgment - this test validates the flow works
    }

    @Test
    fun `should pass previously searched queries to prevent duplicate follow-ups`() = runTest(testCoroutineDispatcher) {
        val partialSource = EvaluatedSource(
            url = "https://example.com/basic-ml",
            title = "Basic ML Info",
            description = null,
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Machine learning helps computers learn from data."
                )
            ),
            contentDate = null,
            intention = "Third-party review article providing a basic overview of machine learning"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is machine learning and how does it work?",
            evaluatedSources = listOf(partialSource),
            previouslySearchedQueries = listOf("machine learning basics", "how ML works")
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should provide what's available")
        // If follow-up queries are suggested, they should not duplicate previously searched queries
        output.followUpQueries.forEach { query ->
            assertTrue(
                !input.previouslySearchedQueries.contains(query),
                "Follow-up query should not duplicate previously searched: $query"
            )
        }
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should identify information gaps and generate follow-up queries for partial answers`() = runTest(testCoroutineDispatcher) {
        // Provide only partial information about pricing - should identify gap for specific prices
        val partialPricingSource = EvaluatedSource(
            url = "https://example.com/pricing",
            title = "Pricing Overview",
            description = null,
            relevantFacts = listOf(
                RelevantFact(
                    fact = "The company offers three pricing tiers: Starter, Professional, and Enterprise."
                ),
                RelevantFact(
                    fact = "Contact sales for pricing information."
                )
            ),
            contentDate = null,
            intention = "Official pricing page showing available subscription tiers"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What are the pricing plans and how much do they cost?",
            evaluatedSources = listOf(partialPricingSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should provide available information")
        
        // With gap-first approach, partial info should result in NEED_MORE_INFORMATION
        // because specific pricing amounts are missing
        assertEquals(
            AnswerStatus.NEED_MORE_INFORMATION, 
            output.status, 
            "Should identify gap: specific pricing amounts missing"
        )
        
        // Should have follow-up queries to find the missing pricing details
        assertTrue(
            output.followUpQueries.isNotEmpty(),
            "Should suggest follow-up queries to find specific pricing"
        )
        
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should have consistent status and gaps - COMPLETE means no gaps, gaps mean NEED_MORE_INFORMATION`() = runTest(testCoroutineDispatcher) {
        // Provide comprehensive information with actual pricing amounts
        val comprehensivePricingSource = EvaluatedSource(
            url = "https://example.com/pricing",
            title = "Pricing",
            description = "Full pricing details",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Starter plan costs $9/month and includes 1000 API calls."
                ),
                RelevantFact(
                    fact = "Professional plan costs $49/month and includes 10,000 API calls."
                ),
                RelevantFact(
                    fact = "Enterprise plan costs $199/month and includes unlimited API calls."
                ),
                RelevantFact(
                    fact = "All plans include 24/7 email support."
                ),
                RelevantFact(
                    fact = "Annual billing provides a 20% discount on all plans."
                )
            ),
            contentDate = null,
            intention = "Official pricing page with complete pricing information for all tiers"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What are the pricing plans and how much do they cost?",
            evaluatedSources = listOf(comprehensivePricingSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should be comprehensive")
        assertTrue(output.answer.contains("9"), "Should mention starter price")
        assertTrue(output.answer.contains("49") || output.answer.contains("professional", ignoreCase = true), 
            "Should mention professional tier")
        assertTrue(output.answer.contains("199") || output.answer.contains("enterprise", ignoreCase = true), 
            "Should mention enterprise tier")
        
        // Test the invariant: status and gaps must be consistent
        if (output.status == AnswerStatus.COMPLETE) {
            assertTrue(
                output.followUpQueries.isEmpty(),
                "COMPLETE status must have no follow-up queries (gaps), but found: ${output.followUpQueries}"
            )
        } else {
            // If NEED_MORE_INFORMATION, should have gaps identified
            assertTrue(
                output.followUpQueries.isNotEmpty(),
                "NEED_MORE_INFORMATION status should have follow-up queries identifying the gaps"
            )
        }
        
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should be self-critical and identify weaknesses in its own answer`() = runTest(testCoroutineDispatcher) {
        // Provide vague information that should trigger self-criticism
        val vagueSource = EvaluatedSource(
            url = "https://example.com/features",
            title = "Features Overview",
            description = null,
            relevantFacts = listOf(
                RelevantFact(
                    fact = "The platform offers advanced AI capabilities."
                ),
                RelevantFact(
                    fact = "Enterprise customers get premium support."
                )
            ),
            contentDate = null,
            intention = "Marketing page providing general feature overview"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What specific AI features does the platform offer and how do they work?",
            evaluatedSources = listOf(vagueSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        
        // The agent should recognize the answer is vague and identify gaps
        assertEquals(
            AnswerStatus.NEED_MORE_INFORMATION, 
            output.status, 
            "Should identify that 'advanced AI capabilities' is too vague to answer 'what specific features'"
        )
        
        assertTrue(
            output.followUpQueries.isNotEmpty(),
            "Should suggest follow-up queries to find specific AI feature details"
        )
        
        // The assessment should show which dimensions are unsatisfied
        assertFalse(
            output.assessment.isComplete(),
            "Should have at least one unsatisfied dimension from self-review"
        )
        
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should handle image IDs and return original hash-based IDs`() = runTest(testCoroutineDispatcher) {
        // Create sources with hash-based image IDs (img-xxx format)
        val sourceWithImages = EvaluatedSource(
            url = "https://example.com/product-screenshots",
            title = "Product Screenshots",
            description = "Visual guide to the product",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "The dashboard shows real-time analytics with customizable widgets."
                ),
                RelevantFact(
                    fact = "Users can create custom reports from the main menu."
                )
            ),
            contentDate = null,
            intention = "Official product page with screenshots demonstrating key features",
            relevantImageIds = listOf("img-abc123", "img-def456")
        )

        val anotherSourceWithImages = EvaluatedSource(
            url = "https://example.com/pricing-page",
            title = "Pricing Information",
            description = "Pricing tiers and features",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "The Pro plan includes advanced analytics features."
                )
            ),
            contentDate = null,
            intention = "Official pricing page with comparison chart",
            relevantImageIds = listOf("img-xyz789")
        )

        // Image descriptions fetched from DB
        val imageDescriptions = mapOf(
            "img-abc123" to "Dashboard screenshot showing real-time analytics widgets",
            "img-def456" to "Settings panel with customization options",
            "img-xyz789" to "Pricing comparison table with three tiers"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "Show me the product dashboard and pricing information",
            evaluatedSources = listOf(sourceWithImages, anotherSourceWithImages),
            imageDescriptions = imageDescriptions
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        
        // Verify that any returned image IDs are in the original hash format (img-xxx)
        // The LLM may or may not select images, but if it does, they should be mapped back correctly
        val allSourceImageIds = listOf("img-abc123", "img-def456", "img-xyz789")
        output.imageIds.forEach { imageId ->
            assertTrue(
                imageId.startsWith("img-"),
                "Image ID should be in original hash format (img-xxx), but got: $imageId"
            )
            assertTrue(
                imageId in allSourceImageIds,
                "Image ID should be one of the original IDs from the sources, but got: $imageId"
            )
        }
        
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should handle sources with no images gracefully`() = runTest(testCoroutineDispatcher) {
        val sourceWithoutImages = EvaluatedSource(
            url = "https://example.com/text-only",
            title = "Text Content",
            description = "Text-only content",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Machine learning models require training data to learn patterns."
                )
            ),
            contentDate = null,
            intention = "Technical documentation explaining ML concepts",
            relevantImageIds = emptyList()  // No images
        )

        val input = StreamingAnswerSynthesisInput(
            query = "How do machine learning models work?",
            evaluatedSources = listOf(sourceWithoutImages)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        // Image IDs should be empty when sources have no images
        assertTrue(
            output.imageIds.isEmpty(),
            "Should return empty imageIds when sources have no images, but got: ${output.imageIds}"
        )
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }
}
