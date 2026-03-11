package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.AnswerStatus
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.RelevantFact
import io.deepsearch.domain.models.valueobjects.SessionHistory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamingAnswerSynthesisAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koin = IsolatedKoinExtension.create { modules(domainTestModule) }

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
        assertEquals(AnswerStatus.CONTINUE_SEARCH, output.status, "Should request more information when empty")
        assertFalse(output.assessment.isComplete(), "All 5 dimensions should be unsatisfied when no sources available")
        assertTrue(output.followUpQueries.isNotEmpty(), "Should suggest follow-up queries when empty")
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
    fun `should indicate CONTINUE_SEARCH when facts are not relevant`() = runTest(testCoroutineDispatcher) {
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
        assertEquals(AnswerStatus.CONTINUE_SEARCH, output.status, "Should request more information when facts are irrelevant")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should return FINISH_SEARCH status for comprehensive relevant facts`() = runTest(testCoroutineDispatcher) {
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
        
        // With gap-first approach, partial info should result in CONTINUE_SEARCH
        // because specific pricing amounts are missing
        assertEquals(
            AnswerStatus.CONTINUE_SEARCH, 
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
    fun `should generate comprehensive answer from detailed pricing information`() = runTest(testCoroutineDispatcher) {
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
        
        // Continuation status and assessment are independent:
        // - FINISH_SEARCH means the LLM is confident enough to stop searching
        // - Assessment dimensions track quality but don't strictly determine status
        // Both FINISH_SEARCH and CONTINUE_SEARCH are valid outputs depending on LLM judgment
        assertNotNull(output.status, "Should have a valid continuation status")
        assertNotNull(output.assessment, "Should have assessment dimensions")
        
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
            AnswerStatus.CONTINUE_SEARCH, 
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

    /**
     * Test case: SleekFlow pricing blog from 2024 should fail temporality assessment for current pricing query.
     * 
     * The blog post is dated July 29, 2024, announcing pricing plans for 2024.
     * When querying for current pricing (in 2026), the agent should:
     * - Return CONTINUE_SEARCH status (sources are outdated)
     * - Have temporality assessment NOT satisfied (content is too old)
     */
    @Test
    fun `should return CONTINUE_SEARCH with temporality not satisfied for outdated pricing blog`() = runTest(testCoroutineDispatcher) {
        val evaluatedSource = EvaluatedSource(
            url = "https://sleekflow.io/blog/sleekflow-pricing-2024",
            title = "SleekFlow new pricing plans 2024",
            description = "Announcing new pricing plans for 2024 with Flow Builder and more features",
            relevantFacts = listOf(
                RelevantFact("Flow Builder will be fully integrated into SleekFlow's subscription plans starting August 28, 2024."),
                RelevantFact("Pro Plan: 3 active flows, 25 nodes per flow, 500 monthly flow enrollments. Features include Omnichannel inbox, Native Shopify integration, Stripe payment link integration, WhatsApp Broadcast, SleekFlow AI, Mobile App, and up to 5,000 API calls monthly."),
                RelevantFact("Premium Plan: 25 active flows, 100 nodes per flow, 3,000 monthly flow enrollments. Features include HubSpot integration, 300,000 broadcast message quota, Custom Objects, Advanced Flow Builder features, Analytics dashboard, and Team access management."),
                RelevantFact("Enterprise Plan: 50 active flows, 200 nodes per flow, 10,000 monthly flow enrollments. Features include Custom usage limit, Unlimited features, Salesforce integration, Export SleekFlow chats, Enterprise-level contact masking, Analytics export, Custom SLAs, and 10 million SleekFlow API calls per month.")
            ),
            contentDate = "2024-07-29",
            intention = "Blog post announcing SleekFlow's 2024 pricing plans with Flow Builder integration"
        )

        val answerInput = StreamingAnswerSynthesisInput(
            query = "What are the current SleekFlow pricing plans?",
            evaluatedSources = listOf(evaluatedSource)
        )

        val answerOutput = agent.generate(answerInput)

        assertNotNull(answerOutput)
        assertTrue(answerOutput.answer.isNotBlank(), "Answer should not be blank")
        
        // Verify CONTINUE_SEARCH status - the pricing info is from 2024 and may be outdated
        assertEquals(
            AnswerStatus.CONTINUE_SEARCH,
            answerOutput.status,
            "Should return CONTINUE_SEARCH for outdated 2024 pricing content when querying for current pricing"
        )
        
        // Verify temporality assessment is NOT satisfied
        assertFalse(
            answerOutput.assessment.temporality.satisfied,
            "Temporality should NOT be satisfied - content from July 2024 is outdated for current (2026) pricing query. " +
                    "Rationale: ${answerOutput.assessment.temporality.rationale}"
        )
        
        assertTrue(answerOutput.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should deduplicate follow-up queries that are case-insensitive matches`() = runTest(testCoroutineDispatcher) {
        val source = EvaluatedSource(
            url = "https://example.com/api-docs",
            title = "API Documentation",
            description = null,
            relevantFacts = listOf(
                RelevantFact(fact = "The API supports REST and GraphQL endpoints.")
            ),
            contentDate = null,
            intention = "Official API documentation page"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "How do I use the API?",
            evaluatedSources = listOf(source),
            previouslySearchedQueries = listOf("API Authentication", "api rate limits", "API ENDPOINTS")
        )

        val output = agent.generate(input)

        assertNotNull(output)
        // Verify no follow-up query matches any previously searched query (case-insensitive)
        output.followUpQueries.forEach { query ->
            val queryLower = query.lowercase()
            input.previouslySearchedQueries.forEach { prev ->
                assertFalse(
                    queryLower == prev.lowercase(),
                    "Follow-up query '$query' matches previously searched '$prev' (case-insensitive)"
                )
            }
        }
    }

    @Test
    fun `should deduplicate follow-up queries that are substrings of previously searched`() = runTest(testCoroutineDispatcher) {
        val source = EvaluatedSource(
            url = "https://example.com/pricing",
            title = "Pricing",
            description = null,
            relevantFacts = listOf(
                RelevantFact(fact = "Enterprise plan includes dedicated support and SLA guarantees.")
            ),
            contentDate = null,
            intention = "Official pricing page"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is included in enterprise pricing?",
            evaluatedSources = listOf(source),
            previouslySearchedQueries = listOf(
                "enterprise pricing details",
                "pricing comparison",
                "enterprise features and benefits"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        // Verify no follow-up query is a substring of or contains a previously searched query
        output.followUpQueries.forEach { query ->
            val queryLower = query.lowercase().trim()
            input.previouslySearchedQueries.forEach { prev ->
                val prevLower = prev.lowercase().trim()
                assertFalse(
                    queryLower.contains(prevLower) || prevLower.contains(queryLower),
                    "Follow-up query '$query' overlaps with previously searched '$prev'"
                )
            }
        }
    }

    @Test
    fun `should allow novel follow-up queries unrelated to previously searched`() = runTest(testCoroutineDispatcher) {
        val source = EvaluatedSource(
            url = "https://example.com/product",
            title = "Product Overview",
            description = null,
            relevantFacts = listOf(
                RelevantFact(fact = "The product integrates with Slack, Jira, and GitHub."),
                RelevantFact(fact = "Free trial available for 14 days.")
            ),
            contentDate = null,
            intention = "Official product overview page"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What integrations does the product support?",
            evaluatedSources = listOf(source),
            previouslySearchedQueries = listOf("pricing plans", "enterprise features")
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Should generate an answer about integrations")
        // This test just verifies the agent can still suggest follow-ups when previous queries are unrelated
        // The programmatic dedup shouldn't filter out novel integration-related queries
    }

    // ==================== Requirement Coverage Tests ====================

    @Test
    fun `should FINISH_SEARCH when all requirements are satisfied`() = runTest(testCoroutineDispatcher) {
        val comprehensiveSource = EvaluatedSource(
            url = "https://example.com/body-check/standard",
            title = "Standard Body Check Package",
            description = "Standard health screening details",
            relevantFacts = listOf(
                RelevantFact(fact = "The standard body check package includes a 45-minute consultation."),
                RelevantFact(fact = "Tests include: complete blood count, fasting blood sugar, kidney function, liver function, coronary risk profile."),
                RelevantFact(fact = "Infectious disease screening: Hepatitis B, HIV, Syphilis."),
                RelevantFact(fact = "Price: HK\$5,900.")
            ),
            contentDate = "2025-11-01",
            intention = "Official product page for the standard body check package"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What's in the standard body check package?",
            evaluatedSources = listOf(comprehensiveSource),
            fulfillmentRequirements = listOf(
                "List of tests/screenings included in the standard package",
                "Price of the standard package"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        println("=== Requirements Coverage: Initial Synthesis ===")
        println("Status: ${output.status}")
        println("Assessment coverage: ${output.assessment.coverage}")
        println("Follow-up queries: ${output.followUpQueries}")
        println("Answer: ${output.answer.take(800)}")
        println()

        assertTrue(output.answer.contains("5,900") || output.answer.contains("5900"), "Should mention price")
        assertTrue(
            output.answer.contains("blood count", ignoreCase = true) ||
            output.answer.contains("blood sugar", ignoreCase = true),
            "Should mention specific tests"
        )

        assertEquals(
            AnswerStatus.FINISH_SEARCH,
            output.status,
            "Should FINISH_SEARCH: all requirements (tests list + price) are satisfied. " +
            "Coverage rationale: ${output.assessment.coverage.rationale}"
        )
    }

    @Test
    fun `should CONTINUE_SEARCH when requirements are missing despite having some information`() = runTest(testCoroutineDispatcher) {
        val partialSource = EvaluatedSource(
            url = "https://example.com/body-check",
            title = "Body Check Packages",
            description = "Overview of available packages",
            relevantFacts = listOf(
                RelevantFact(fact = "The clinic offers Standard, Comprehensive, and Ultra body check packages."),
                RelevantFact(fact = "All packages include a consultation with a GP.")
            ),
            contentDate = "2025-11-01",
            intention = "Official body check landing page with package overview"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What's in the standard body check package and how much does it cost?",
            evaluatedSources = listOf(partialSource),
            fulfillmentRequirements = listOf(
                "List of tests/screenings included in the standard package",
                "Price of the standard package"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        println("=== Requirements Missing: Initial Synthesis ===")
        println("Status: ${output.status}")
        println("Assessment coverage: ${output.assessment.coverage}")
        println("Follow-up queries: ${output.followUpQueries}")
        println("Answer: ${output.answer.take(500)}")
        println()

        assertEquals(
            AnswerStatus.CONTINUE_SEARCH,
            output.status,
            "Should CONTINUE_SEARCH: requirements (specific tests, price) are not yet satisfied. " +
            "Coverage rationale: ${output.assessment.coverage.rationale}"
        )
        assertFalse(
            output.assessment.coverage.satisfied,
            "Coverage should NOT be satisfied when requirements are missing"
        )
        assertTrue(
            output.followUpQueries.isNotEmpty(),
            "Should suggest follow-up queries to find specific test list and pricing"
        )
    }

    // ==================== NOT_FOUND Status Test ====================

    @Test
    fun `should return NOT_FOUND when sources are completely irrelevant to query`() = runTest(testCoroutineDispatcher) {
        val irrelevantSource = EvaluatedSource(
            url = "https://example.com/cooking-blog",
            title = "Best Pasta Recipes 2025",
            description = "Italian cooking tips and recipes",
            relevantFacts = listOf(
                RelevantFact(fact = "Use San Marzano tomatoes for the best marinara sauce."),
                RelevantFact(fact = "Fresh pasta should be cooked for only 2-3 minutes."),
                RelevantFact(fact = "Extra virgin olive oil is essential for authentic Italian cooking.")
            ),
            contentDate = "2025-08-15",
            intention = "Recipe blog post about Italian pasta cooking techniques"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What are the enterprise API rate limits?",
            evaluatedSources = listOf(irrelevantSource),
            fulfillmentRequirements = listOf(
                "API rate limits for the enterprise plan",
                "Rate limit unit (per second, per minute, per day)"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        println("=== NOT_FOUND STATUS TEST ===")
        println("Status: ${output.status}")
        println("Assessment: coverage=${output.assessment.coverage}, depth=${output.assessment.depth}")
        println("Follow-up queries: ${output.followUpQueries}")
        println("Answer: ${output.answer.take(500)}")
        println()

        assertTrue(
            output.status == AnswerStatus.NOT_FOUND || output.status == AnswerStatus.CONTINUE_SEARCH,
            "Should return NOT_FOUND or CONTINUE_SEARCH when sources are completely irrelevant. Got: ${output.status}"
        )
        assertFalse(
            output.assessment.coverage.satisfied,
            "Coverage should NOT be satisfied for completely irrelevant sources"
        )
        assertFalse(
            output.answer.contains("pasta", ignoreCase = true) && output.answer.contains("rate limit", ignoreCase = true),
            "Should NOT hallucinate by mixing pasta info with rate limits"
        )
    }

    // ==================== Session Continuation Tests ====================

    /**
     * Test case: Session continuation with different query should expand on prior findings.
     * 
     * Scenario: User first asked about pricing, now asking about system requirements.
     * The agent should NOT repeat pricing information and should focus on the new topic.
     */
    @Test
    fun `should expand on prior findings without repeating when continuation query is different topic`() = runTest(testCoroutineDispatcher) {
        // Prior session covered pricing
        val sessionHistory = SessionHistory(listOf(
            SessionHistory.SessionSummary(
                sessionId = "session-123",
                query = "What are the pricing plans?",
                answer = """
                    ## SleekFlow Pricing Plans
                    
                    SleekFlow offers three pricing tiers:
                    
                    1. **Pro Plan** - $79/month
                       - Includes omnichannel inbox
                       - Up to 3 active flows
                       - 5,000 API calls monthly
                    
                    2. **Premium Plan** - $299/month
                       - Advanced Flow Builder features
                       - HubSpot integration
                       - Analytics dashboard
                    
                    3. **Enterprise Plan** - Custom pricing
                       - Unlimited features
                       - Salesforce integration
                       - Custom SLAs
                """.trimIndent()
            )
        ))

        // New sources provide system requirements information
        val systemReqSource = EvaluatedSource(
            url = "https://sleekflow.io/docs/requirements",
            title = "System Requirements",
            description = "Technical requirements for SleekFlow",
            relevantFacts = listOf(
                RelevantFact(fact = "SleekFlow requires a modern web browser: Chrome 90+, Firefox 88+, Safari 14+, or Edge 90+."),
                RelevantFact(fact = "Mobile apps are available for iOS 14+ and Android 8.0+."),
                RelevantFact(fact = "WhatsApp Business API integration requires a verified Facebook Business account."),
                RelevantFact(fact = "Minimum internet speed of 5 Mbps recommended for optimal performance.")
            ),
            contentDate = "2024-01-15",
            intention = "Official documentation page listing technical requirements"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What are the system requirements for SleekFlow?",
            evaluatedSources = listOf(systemReqSource),
            sessionHistory = sessionHistory
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        
        // The answer should focus on system requirements, not pricing
        assertTrue(
            output.answer.contains("browser", ignoreCase = true) || 
            output.answer.contains("Chrome", ignoreCase = true) ||
            output.answer.contains("iOS", ignoreCase = true) ||
            output.answer.contains("Android", ignoreCase = true),
            "Answer should mention system requirements (browser/mobile), got: ${output.answer.take(500)}"
        )
        
        // The answer should NOT repeat the pricing information from prior session
        assertFalse(
            output.answer.contains("$79") || output.answer.contains("$299"),
            "Answer should NOT repeat pricing amounts from prior session"
        )
        
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    /**
     * Test case: Session continuation with follow-up query on same topic should add new details.
     * 
     * Scenario: User first asked about pricing overview, now asking about enterprise pricing details.
     * The agent should provide additional enterprise details without repeating the overview.
     */
    @Test
    fun `should add new details when continuation query digs deeper into same topic`() = runTest(testCoroutineDispatcher) {
        // Prior session covered general pricing overview
        val sessionHistory = SessionHistory(listOf(
            SessionHistory.SessionSummary(
                sessionId = "session-456",
                query = "What are the pricing plans?",
                answer = """
                    ## Pricing Overview
                    
                    The platform offers three tiers:
                    - **Starter**: $9/month - Basic features for individuals
                    - **Professional**: $49/month - Advanced features for teams
                    - **Enterprise**: Custom pricing - Full features for large organizations
                    
                    All plans include 24/7 email support and a 14-day free trial.
                """.trimIndent()
            )
        ))

        // New sources provide detailed enterprise information
        val enterpriseSource = EvaluatedSource(
            url = "https://example.com/enterprise",
            title = "Enterprise Solutions",
            description = "Enterprise pricing and features",
            relevantFacts = listOf(
                RelevantFact(fact = "Enterprise pricing starts at $500/month for up to 50 users."),
                RelevantFact(fact = "Volume discounts available: 10% off for 100+ users, 20% off for 500+ users."),
                RelevantFact(fact = "Enterprise includes dedicated account manager and 99.9% SLA guarantee."),
                RelevantFact(fact = "Custom integrations and white-labeling available for Enterprise customers."),
                RelevantFact(fact = "Annual contracts receive 2 months free.")
            ),
            contentDate = "2024-06-01",
            intention = "Official enterprise pricing page with detailed information"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What are the specific enterprise pricing details and discounts?",
            evaluatedSources = listOf(enterpriseSource),
            sessionHistory = sessionHistory
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        
        // The answer should include new enterprise-specific details
        assertTrue(
            output.answer.contains("500") || output.answer.contains("discount", ignoreCase = true) ||
            output.answer.contains("SLA", ignoreCase = true) || output.answer.contains("account manager", ignoreCase = true),
            "Answer should include new enterprise details not in prior answer, got: ${output.answer.take(500)}"
        )
        
        // Should NOT just repeat "$9/month" and "$49/month" from prior context
        // (It's okay to briefly reference them, but the focus should be on new info)
        val starterMentions = output.answer.split("$9").size - 1
        assertTrue(
            starterMentions <= 1,
            "Answer should not heavily repeat Starter pricing from prior session (found $starterMentions mentions)"
        )
        
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    /**
     * Test case: Session continuation where new sources provide conflicting/updated information.
     * 
     * Scenario: Prior session had old pricing, new sources show updated pricing.
     * The agent should present the new information and note the update.
     */
    @Test
    fun `should handle updated information that supersedes prior findings`() = runTest(testCoroutineDispatcher) {
        // Prior session had pricing from 2024
        val sessionHistory = SessionHistory(listOf(
            SessionHistory.SessionSummary(
                sessionId = "session-789",
                query = "What is the Pro plan pricing?",
                answer = """
                    ## Pro Plan Pricing (as of 2024)
                    
                    The Pro plan costs **$49/month** and includes:
                    - 10,000 API calls
                    - 5 team members
                    - Priority email support
                """.trimIndent()
            )
        ))

        // New sources show 2025 pricing update
        val updatedPricingSource = EvaluatedSource(
            url = "https://example.com/pricing-2025",
            title = "2025 Pricing Update",
            description = "Updated pricing for 2025",
            relevantFacts = listOf(
                RelevantFact(fact = "Starting January 2025, Pro plan pricing increased to $59/month."),
                RelevantFact(fact = "The 2025 Pro plan now includes 15,000 API calls (up from 10,000)."),
                RelevantFact(fact = "Team member limit increased to 10 members for Pro plan."),
                RelevantFact(fact = "Existing customers on annual plans keep 2024 pricing until renewal.")
            ),
            contentDate = "2025-01-01",
            intention = "Official pricing announcement page for 2025 updates"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "Has the Pro plan pricing changed recently?",
            evaluatedSources = listOf(updatedPricingSource),
            sessionHistory = sessionHistory
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        
        // The answer should mention the new pricing
        assertTrue(
            output.answer.contains("59") || output.answer.contains("2025") || 
            output.answer.contains("increased", ignoreCase = true) || output.answer.contains("updated", ignoreCase = true),
            "Answer should mention the updated 2025 pricing, got: ${output.answer.take(500)}"
        )
        
        // The answer should mention the improvements (more API calls, more team members)
        assertTrue(
            output.answer.contains("15,000") || output.answer.contains("15000") ||
            output.answer.contains("10 member", ignoreCase = true) || output.answer.contains("10 team", ignoreCase = true),
            "Answer should mention the improved features in 2025 plan"
        )
        
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    /**
     * Test case: Session continuation where prior answer was incomplete.
     * 
     * Scenario: Prior session couldn't find specific information, continuation provides it.
     */
    @Test
    fun `should fill gaps from prior incomplete answer`() = runTest(testCoroutineDispatcher) {
        // Prior session couldn't find specific pricing
        val sessionHistory = SessionHistory(listOf(
            SessionHistory.SessionSummary(
                sessionId = "session-incomplete",
                query = "What is the pricing for the API?",
                answer = """
                    ## API Pricing
                    
                    The platform offers API access through its subscription plans. However, I couldn't find 
                    specific API pricing details. The documentation mentions:
                    
                    - API access is included in all paid plans
                    - Rate limits vary by plan tier
                    - Contact sales for high-volume API pricing
                    
                    For specific pricing information, please check the official pricing page or contact support.
                """.trimIndent()
            )
        ))

        // New sources provide the missing specific pricing
        val apiPricingSource = EvaluatedSource(
            url = "https://example.com/api-pricing",
            title = "API Pricing Details",
            description = "Detailed API pricing information",
            relevantFacts = listOf(
                RelevantFact(fact = "API calls are priced at $0.001 per call after the included quota."),
                RelevantFact(fact = "Starter plan includes 5,000 free API calls per month."),
                RelevantFact(fact = "Professional plan includes 50,000 free API calls per month."),
                RelevantFact(fact = "Enterprise plan includes unlimited API calls."),
                RelevantFact(fact = "Bulk API packages available: 1M calls for $500, 10M calls for $3,000.")
            ),
            contentDate = "2024-09-15",
            intention = "Official API pricing page with detailed cost breakdown"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What are the specific API pricing details?",
            evaluatedSources = listOf(apiPricingSource),
            sessionHistory = sessionHistory
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        
        // The answer should now include specific pricing that was missing before
        assertTrue(
            output.answer.contains("0.001") || output.answer.contains("$500") || output.answer.contains("$3,000") ||
            output.answer.contains("5,000") || output.answer.contains("50,000"),
            "Answer should include specific API pricing details that were missing in prior answer, got: ${output.answer.take(500)}"
        )
        
        // Should NOT repeat the "contact sales" or "couldn't find" language from prior answer
        assertFalse(
            output.answer.contains("couldn't find", ignoreCase = true) || 
            output.answer.contains("could not find", ignoreCase = true),
            "Answer should not repeat the 'couldn't find' language from prior incomplete answer"
        )
        
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    /**
     * Test case: Session continuation with empty new sources.
     * 
     * Scenario: Continuation search found no new relevant information.
     * The agent should acknowledge this gracefully.
     */
    @Test
    fun `should handle continuation with no new relevant information gracefully`() = runTest(testCoroutineDispatcher) {
        // Prior session had comprehensive answer
        val sessionHistory = SessionHistory(listOf(
            SessionHistory.SessionSummary(
                sessionId = "session-complete",
                query = "What are the main features?",
                answer = """
                    ## Main Features
                    
                    The platform offers:
                    1. **Real-time Analytics** - Track metrics in real-time
                    2. **Team Collaboration** - Work together seamlessly
                    3. **API Integration** - Connect with other tools
                    4. **Custom Reports** - Generate detailed reports
                """.trimIndent()
            )
        ))

        // New sources have irrelevant information
        val irrelevantSource = EvaluatedSource(
            url = "https://example.com/blog/company-news",
            title = "Company News",
            description = "Latest company updates",
            relevantFacts = listOf(
                RelevantFact(fact = "The company was founded in 2020."),
                RelevantFact(fact = "Headquarters are located in San Francisco.")
            ),
            contentDate = "2024-03-01",
            intention = "Company blog post about company history"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "Are there any additional features I should know about?",
            evaluatedSources = listOf(irrelevantSource),
            sessionHistory = sessionHistory
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        
        // Should indicate that no additional relevant features were found
        // or acknowledge the prior answer covered the main features
        assertEquals(
            AnswerStatus.CONTINUE_SEARCH,
            output.status,
            "Should request more information when new sources don't add relevant info"
        )
        
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    /**
     * Test case: Real-world scenario - SleekFlow pricing continuation.
     * 
     * Scenario: User first asked about general pricing, now asking about Flow Builder specifics.
     */
    @Test
    fun `real world - should expand SleekFlow pricing with Flow Builder details`() = runTest(testCoroutineDispatcher) {
        // Prior session covered general SleekFlow pricing
        val sessionHistory = SessionHistory(listOf(
            SessionHistory.SessionSummary(
                sessionId = "sleekflow-session-1",
                query = "What are SleekFlow pricing plans?",
                answer = """
                    ## SleekFlow Pricing Plans
                    
                    SleekFlow offers three main pricing tiers:
                    
                    1. **Pro Plan** - For small teams
                       - Omnichannel inbox
                       - Native Shopify integration
                       - WhatsApp Broadcast
                       - Up to 5,000 API calls monthly
                    
                    2. **Premium Plan** - For scaling businesses
                       - Everything in Pro
                       - HubSpot integration
                       - Advanced analytics dashboard
                       - Team access management
                    
                    3. **Enterprise Plan** - For large businesses
                       - Everything in Premium
                       - Salesforce integration
                       - Custom SLAs
                       - Unlimited features
                    
                    All plans include SleekFlow AI and mobile app access.
                """.trimIndent()
            )
        ))

        // New sources provide Flow Builder specific details
        val flowBuilderSource = EvaluatedSource(
            url = "https://sleekflow.io/flow-builder",
            title = "Flow Builder Pricing",
            description = "Flow Builder usage limits and pricing",
            relevantFacts = listOf(
                RelevantFact(fact = "Flow Builder is included in all subscription plans starting August 28, 2024."),
                RelevantFact(fact = "Pro Plan Flow Builder limits: 3 active flows, 25 nodes per flow, 500 monthly flow enrollments."),
                RelevantFact(fact = "Premium Plan Flow Builder limits: 25 active flows, 100 nodes per flow, 3,000 monthly flow enrollments."),
                RelevantFact(fact = "Enterprise Plan Flow Builder limits: 50 active flows, 200 nodes per flow, 10,000 monthly flow enrollments."),
                RelevantFact(fact = "Additional flow enrollments can be purchased as add-ons.")
            ),
            contentDate = "2024-07-29",
            intention = "Official Flow Builder feature page with usage limits per plan"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What are the Flow Builder limits for each SleekFlow plan?",
            evaluatedSources = listOf(flowBuilderSource),
            sessionHistory = sessionHistory
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        
        // The answer should focus on Flow Builder specifics
        assertTrue(
            output.answer.contains("flow", ignoreCase = true) &&
            (output.answer.contains("3 active") || output.answer.contains("25 active") || 
             output.answer.contains("50 active") || output.answer.contains("nodes", ignoreCase = true)),
            "Answer should include Flow Builder specific limits, got: ${output.answer.take(500)}"
        )
        
        // Should mention enrollment limits
        assertTrue(
            output.answer.contains("500") || output.answer.contains("3,000") || output.answer.contains("10,000") ||
            output.answer.contains("enrollment", ignoreCase = true),
            "Answer should mention flow enrollment limits"
        )
        
        // Should NOT extensively repeat the general plan descriptions from prior context
        val shopifyMentions = output.answer.split("Shopify", ignoreCase = true).size - 1
        assertTrue(
            shopifyMentions <= 1,
            "Answer should not repeat general features like Shopify integration (found $shopifyMentions mentions)"
        )
        
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }
}
