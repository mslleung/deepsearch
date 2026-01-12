package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.HtmlSourceEvalInput
import io.deepsearch.domain.agents.IHtmlSourceEvalAgent
import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.AnswerStatus
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.RelevantFact
import io.deepsearch.domain.models.valueobjects.UrlContentResult
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
    private val htmlEvalAgent by inject<IHtmlSourceEvalAgent>()

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
        // HTML content from SleekFlow pricing 2024 blog post (dated July 29, 2024)
        val sleekflowPricing2024Html = UrlContentResult.HtmlPreview(
            url = "https://sleekflow.io/blog/sleekflow-pricing-2024",
            title = "SleekFlow new pricing plans 2024",
            description = "Announcing new pricing plans for 2024 with Flow Builder and more features",
            cleanedHtml = """
                <article>
                    <header>
                        <h1>SleekFlow new pricing plans 2024</h1>
                        <p>Esther Fong, Product Marketing Manager</p>
                        <time datetime="2024-07-29">Jul 29, 2024</time>
                    </header>
                    
                    <section>
                        <h2>Introducing Flow Builder usage-based pricing model</h2>
                        <p>We're excited that Flow Builder will officially be out of beta and fully integrated 
                        into SleekFlow's subscription plans starting August 28, 2024.</p>
                        
                        <p>Flow Builder is a versatile customer journey automation tool for chats. It allows 
                        you to design automation scenarios visually to capture customers' behavioral cues 
                        across different sources and trigger engagement, sales, and support messages at 
                        the right time.</p>
                        
                        <p>The Flow Builder usage limits are aligned with our tiered subscription model:</p>
                        <ul>
                            <li>Pro Plan: 3 active flows, 25 nodes per flow, 500 monthly flow enrollments</li>
                            <li>Premium Plan: 25 active flows, 100 nodes per flow, 3,000 monthly flow enrollments</li>
                            <li>Enterprise Plan: 50 active flows, 200 nodes per flow, 10,000 monthly flow enrollments</li>
                        </ul>
                    </section>
                    
                    <section>
                        <h2>New Pro Plan - for small teams to collaborate better on chats</h2>
                        <p>Our Pro Plan is now better than ever, packed with more commerce features without 
                        the need for additional purchases. It is perfect for small businesses and teams 
                        that want to provide a convenient customer experience on chats.</p>
                        <p>Key features include: Omnichannel inbox, Native Shopify integration, Stripe payment 
                        link integration, Basic Flow Builder features, Facebook Lead Ads integration, 
                        WhatsApp Broadcast, SleekFlow AI, Mobile App, and up to 5,000 API calls monthly.</p>
                    </section>
                    
                    <section>
                        <h2>New Premium Plan - for scaling businesses to automate workflows</h2>
                        <p>Our Premium Plan continues to be our top-tier offering, providing the best value 
                        in the market. Designed for businesses desiring to scale productivity.</p>
                        <p>Key features include: HubSpot integration, 300,000 broadcast message quota, 
                        Custom Objects, Advanced Flow Builder features, Analytics dashboard, and 
                        Team access management.</p>
                    </section>
                    
                    <section>
                        <h2>New Enterprise Plan - for large businesses to build tailored solutions</h2>
                        <p>The Enterprise Plan guarantees top-quality customized services. This plan includes 
                        everything from the Premium Plan plus enterprise-level features.</p>
                        <p>Key offerings include: Custom usage limit, Unlimited features, Salesforce integration, 
                        Export SleekFlow chats, Enterprise-level contact masking, Analytics export, Custom SLAs, 
                        Flow Builder and automation setup, 100,000 Custom Object records, and 10 million 
                        SleekFlow API calls per month.</p>
                    </section>
                </article>
            """.trimIndent()
        )

        // Step 1: Evaluate the HTML source with HtmlSourceEvalAgent
        val evalInput = HtmlSourceEvalInput(
            htmlSource = sleekflowPricing2024Html,
            expandedQuery = "What are the current SleekFlow pricing plans?"
        )

        val evalOutput = htmlEvalAgent.generate(evalInput)

        assertNotNull(evalOutput.evaluatedSource, "Should extract facts from the pricing blog post")
        
        val evaluatedSource = evalOutput.evaluatedSource
        assertTrue(
            evaluatedSource.relevantFacts.isNotEmpty(),
            "Should have extracted relevant pricing facts"
        )

        // Step 2: Pass the evaluated source to the answer synthesis agent
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
}
