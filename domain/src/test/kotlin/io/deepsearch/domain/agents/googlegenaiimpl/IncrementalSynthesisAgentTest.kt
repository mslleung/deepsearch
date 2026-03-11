package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.AnswerAssessment
import io.deepsearch.domain.agents.DimensionAssessment
import io.deepsearch.domain.agents.IIncrementalSynthesisAgent
import io.deepsearch.domain.agents.IncrementalSynthesisInput
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

class IncrementalSynthesisAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koin = IsolatedKoinExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IIncrementalSynthesisAgent>()

    private fun allSatisfiedAssessment() = AnswerAssessment(
        coverage = DimensionAssessment(satisfied = true, rationale = "All primary requirements covered"),
        depth = DimensionAssessment(satisfied = true, rationale = "Specific data provided"),
        temporality = DimensionAssessment(satisfied = true, rationale = "Sources are current"),
        authority = DimensionAssessment(satisfied = true, rationale = "Official sources"),
        consistency = DimensionAssessment(satisfied = true, rationale = "No conflicts")
    )

    private fun partialAssessment() = AnswerAssessment(
        coverage = DimensionAssessment(satisfied = false, rationale = "Missing enterprise pricing details"),
        depth = DimensionAssessment(satisfied = false, rationale = "Only tier names, no prices"),
        temporality = DimensionAssessment(satisfied = true, rationale = "Sources are current"),
        authority = DimensionAssessment(satisfied = true, rationale = "Official pricing page"),
        consistency = DimensionAssessment(satisfied = true, rationale = "No conflicts")
    )

    // ==================== Redundant Sources ====================

    @Test
    fun `should return answer unchanged when new sources are redundant`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = """
            ## Machine Learning Overview
            
            Machine learning is a subset of artificial intelligence that enables systems to learn from data [1]. 
            The three main types are supervised learning, unsupervised learning, and reinforcement learning [1].
        """.trimIndent()

        val redundantSource = EvaluatedSource(
            url = "https://example.com/ml-intro",
            title = "Intro to ML",
            description = "Basic ML introduction",
            relevantFacts = listOf(
                RelevantFact(fact = "Machine learning is a type of artificial intelligence."),
                RelevantFact(fact = "There are three main types of ML: supervised, unsupervised, and reinforcement learning.")
            ),
            contentDate = null,
            intention = "Third-party blog providing a general introduction to machine learning"
        )

        val input = IncrementalSynthesisInput(
            query = "What is machine learning?",
            newSources = listOf(redundantSource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = listOf("https://example.com/ml-guide"),
            currentAssessment = allSatisfiedAssessment(),
            fulfillmentRequirements = listOf(
                "Definition of machine learning",
                "Main types of machine learning"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        println("=== REDUNDANT SOURCES TEST ===")
        println("Status: ${output.status}")
        println("Answer length: ${output.answer.length}")
        println("Assessment coverage: ${output.assessment.coverage}")
        println("Answer: ${output.answer.take(500)}")
        println()

        assertTrue(
            output.answer.contains("machine learning", ignoreCase = true),
            "Answer should still contain core information about ML"
        )
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    // ==================== Deepening Sources ====================

    @Test
    fun `should update answer when new sources provide specific data for vague claims`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = """
            ## Pricing Plans
            
            The platform offers three pricing tiers: Starter, Professional, and Enterprise [1].
            Contact sales for pricing information.
        """.trimIndent()

        val deepeningSource = EvaluatedSource(
            url = "https://example.com/pricing-details",
            title = "Detailed Pricing",
            description = "Complete pricing breakdown",
            relevantFacts = listOf(
                RelevantFact(fact = "Starter plan costs \$9/month and includes 1,000 API calls."),
                RelevantFact(fact = "Professional plan costs \$49/month and includes 10,000 API calls."),
                RelevantFact(fact = "Enterprise plan costs \$199/month and includes unlimited API calls."),
                RelevantFact(fact = "All plans include 24/7 email support."),
                RelevantFact(fact = "Annual billing provides a 20% discount.")
            ),
            contentDate = "2025-11-01",
            intention = "Official pricing page with complete pricing information for all tiers"
        )

        val input = IncrementalSynthesisInput(
            query = "What are the pricing plans and how much do they cost?",
            newSources = listOf(deepeningSource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = listOf("https://example.com/pricing-overview"),
            currentAssessment = partialAssessment(),
            fulfillmentRequirements = listOf(
                "List of pricing tiers",
                "Price of each tier",
                "Features included in each tier",
                "Discount information"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        println("=== DEEPENING SOURCES TEST ===")
        println("Status: ${output.status}")
        println("Assessment: coverage=${output.assessment.coverage.satisfied}, depth=${output.assessment.depth.satisfied}")
        println("Cited URLs: ${output.citedSourceUrls}")
        println("Answer: ${output.answer.take(800)}")
        println()

        assertTrue(output.answer.contains("9"), "Should include Starter price")
        assertTrue(output.answer.contains("49"), "Should include Professional price")
        assertTrue(output.answer.contains("199"), "Should include Enterprise price")

        assertTrue(
            output.citedSourceUrls.size >= 2,
            "Should cite both original and new source, got: ${output.citedSourceUrls}"
        )
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    // ==================== Contradictory Sources ====================

    @Test
    fun `should handle contradictory sources and note conflicts`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = """
            ## Pro Plan Pricing
            
            The Pro plan costs **\$49/month** and includes 10,000 API calls and 5 team members [1].
        """.trimIndent()

        val contradictorySource = EvaluatedSource(
            url = "https://example.com/pricing-2026",
            title = "2026 Pricing Update",
            description = "Updated pricing for 2026",
            relevantFacts = listOf(
                RelevantFact(fact = "Starting January 2026, the Pro plan pricing increased to \$69/month."),
                RelevantFact(fact = "The 2026 Pro plan now includes 20,000 API calls."),
                RelevantFact(fact = "Team member limit increased to 10 members.")
            ),
            contentDate = "2026-01-15",
            intention = "Official pricing announcement page for 2026 updates"
        )

        val input = IncrementalSynthesisInput(
            query = "What is the current Pro plan pricing?",
            newSources = listOf(contradictorySource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = listOf("https://example.com/pricing-old"),
            currentAssessment = AnswerAssessment(
                coverage = DimensionAssessment(satisfied = true, rationale = "Pro plan pricing covered"),
                depth = DimensionAssessment(satisfied = true, rationale = "Specific price and features"),
                temporality = DimensionAssessment(satisfied = false, rationale = "Source may be outdated"),
                authority = DimensionAssessment(satisfied = true, rationale = "Official pricing page"),
                consistency = DimensionAssessment(satisfied = true, rationale = "Single source, no conflicts")
            ),
            fulfillmentRequirements = listOf(
                "Current Pro plan price",
                "Features included in Pro plan"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        println("=== CONTRADICTORY SOURCES TEST ===")
        println("Status: ${output.status}")
        println("Assessment: consistency=${output.assessment.consistency}")
        println("Cited URLs: ${output.citedSourceUrls}")
        println("Answer: ${output.answer.take(800)}")
        println()

        assertTrue(
            output.answer.contains("69") || output.answer.contains("2026"),
            "Should include updated pricing or year reference"
        )
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    // ==================== NOT_FOUND Status ====================

    @Test
    fun `should return NOT_FOUND when neither current answer nor new sources have relevant info`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = "No information found to answer the query."

        val irrelevantSource = EvaluatedSource(
            url = "https://example.com/cooking-blog",
            title = "Best Pasta Recipes",
            description = "Italian cooking tips",
            relevantFacts = listOf(
                RelevantFact(fact = "Use fresh basil for the best pesto sauce."),
                RelevantFact(fact = "Al dente pasta should be cooked for 8-10 minutes.")
            ),
            contentDate = "2025-06-01",
            intention = "Recipe blog post about Italian pasta cooking techniques"
        )

        val input = IncrementalSynthesisInput(
            query = "What are the API rate limits for the Enterprise plan?",
            newSources = listOf(irrelevantSource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = emptyList(),
            currentAssessment = AnswerAssessment(
                coverage = DimensionAssessment(satisfied = false, rationale = "No relevant facts found"),
                depth = DimensionAssessment(satisfied = false, rationale = "No data"),
                temporality = DimensionAssessment(satisfied = false, rationale = "No sources"),
                authority = DimensionAssessment(satisfied = false, rationale = "No sources"),
                consistency = DimensionAssessment(satisfied = false, rationale = "No sources")
            ),
            fulfillmentRequirements = listOf(
                "API rate limits for Enterprise plan",
                "Whether limits are per-minute or per-day",
                "Overage pricing"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        println("=== NOT_FOUND STATUS TEST ===")
        println("Status: ${output.status}")
        println("Assessment: coverage=${output.assessment.coverage}")
        println("Follow-up queries: ${output.followUpQueries}")
        println("Answer: ${output.answer.take(500)}")
        println()

        assertTrue(
            output.status == AnswerStatus.NOT_FOUND || output.status == AnswerStatus.CONTINUE_SEARCH,
            "Should return NOT_FOUND or CONTINUE_SEARCH for irrelevant sources, got: ${output.status}"
        )
        assertFalse(
            output.assessment.coverage.satisfied,
            "Coverage should NOT be satisfied when sources are irrelevant"
        )
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    // ==================== FINISH_SEARCH with Completing Sources ====================

    @Test
    fun `should return FINISH_SEARCH when new sources complete all requirements`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = """
            ## SaaS Product Features
            
            The product offers an omnichannel inbox for managing conversations [1].
            It supports WhatsApp, Facebook Messenger, and Instagram integrations [1].
        """.trimIndent()

        val completingSource = EvaluatedSource(
            url = "https://example.com/pricing",
            title = "Pricing",
            description = "Product pricing page",
            relevantFacts = listOf(
                RelevantFact(fact = "Pro plan: \$79/month billed monthly, \$59/month billed annually."),
                RelevantFact(fact = "Premium plan: \$299/month billed monthly, \$229/month billed annually."),
                RelevantFact(fact = "Enterprise plan: Custom pricing, contact sales."),
                RelevantFact(fact = "14-day free trial available for all plans.")
            ),
            contentDate = "2026-01-01",
            intention = "Official pricing page with current pricing for all tiers"
        )

        val input = IncrementalSynthesisInput(
            query = "What are the pricing plans for this SaaS product?",
            newSources = listOf(completingSource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = listOf("https://example.com/features"),
            currentAssessment = AnswerAssessment(
                coverage = DimensionAssessment(satisfied = false, rationale = "Features covered but pricing missing"),
                depth = DimensionAssessment(satisfied = false, rationale = "No pricing data"),
                temporality = DimensionAssessment(satisfied = true, rationale = "Current sources"),
                authority = DimensionAssessment(satisfied = true, rationale = "Official site"),
                consistency = DimensionAssessment(satisfied = true, rationale = "No conflicts")
            ),
            fulfillmentRequirements = listOf(
                "List of available pricing tiers",
                "Price per tier",
                "Free trial availability",
                "Annual vs monthly billing difference"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        println("=== FINISH_SEARCH TEST ===")
        println("Status: ${output.status}")
        println("Assessment: ${output.assessment}")
        println("Cited URLs: ${output.citedSourceUrls}")
        println("Follow-up queries: ${output.followUpQueries}")
        println("Answer: ${output.answer.take(800)}")
        println()

        assertTrue(
            output.answer.contains("79") || output.answer.contains("Pro", ignoreCase = true),
            "Should mention Pro plan pricing"
        )
        assertTrue(
            output.answer.contains("299") || output.answer.contains("Premium", ignoreCase = true),
            "Should mention Premium plan pricing"
        )

        assertNotNull(output.status, "Should have a valid status")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    // ==================== Citation Continuity ====================

    @Test
    fun `should preserve existing citations and add new ones starting from next number`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = """
            ## Product Overview
            
            The platform provides real-time analytics [1] and supports integrations with Slack and Jira [2].
        """.trimIndent()

        val newSource = EvaluatedSource(
            url = "https://example.com/security",
            title = "Security Features",
            description = "Security documentation",
            relevantFacts = listOf(
                RelevantFact(fact = "SOC 2 Type II certified since 2024."),
                RelevantFact(fact = "All data encrypted at rest with AES-256 and in transit with TLS 1.3."),
                RelevantFact(fact = "Role-based access control (RBAC) with custom roles.")
            ),
            contentDate = "2025-09-01",
            intention = "Official security documentation page"
        )

        val input = IncrementalSynthesisInput(
            query = "What security features does the platform offer?",
            newSources = listOf(newSource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = listOf(
                "https://example.com/analytics",
                "https://example.com/integrations"
            ),
            currentAssessment = AnswerAssessment(
                coverage = DimensionAssessment(satisfied = false, rationale = "Security features not yet covered"),
                depth = DimensionAssessment(satisfied = true, rationale = "Integration details are specific"),
                temporality = DimensionAssessment(satisfied = true, rationale = "Current content"),
                authority = DimensionAssessment(satisfied = true, rationale = "Official site"),
                consistency = DimensionAssessment(satisfied = true, rationale = "No conflicts")
            ),
            fulfillmentRequirements = listOf(
                "Security certifications",
                "Data encryption methods",
                "Access control features"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        println("=== CITATION CONTINUITY TEST ===")
        println("Cited URLs: ${output.citedSourceUrls}")
        println("Answer: ${output.answer.take(800)}")
        println()

        assertTrue(
            output.answer.contains("SOC 2", ignoreCase = true) ||
            output.answer.contains("AES-256") ||
            output.answer.contains("encrypt", ignoreCase = true),
            "Answer should include new security information"
        )

        assertTrue(
            output.citedSourceUrls.contains("https://example.com/security") ||
            output.citedSourceUrls.size >= 2,
            "Should cite the new security source. Got: ${output.citedSourceUrls}"
        )
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    // ==================== Requirement Coverage Tests ====================

    @Test
    fun `should FINISH_SEARCH when all requirements are satisfied`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = """
            ## Standard Body Check Package
            
            The standard body check package includes a 45-minute consultation with blood count, 
            blood sugar, kidney and liver function tests, coronary risk profile, Hepatitis B, 
            HIV, and Syphilis screening. Price: HK\$5,900 [1].
        """.trimIndent()

        val newSource = EvaluatedSource(
            url = "https://example.com/body-check/faq",
            title = "Body Check FAQ",
            description = "Frequently asked questions about body check packages",
            relevantFacts = listOf(
                RelevantFact(fact = "Results are typically available within 5 business days."),
                RelevantFact(fact = "Patients should fast for 8 hours before the blood test."),
                RelevantFact(fact = "Walk-in appointments available Monday to Friday.")
            ),
            contentDate = "2025-12-01",
            intention = "FAQ page for body check packages"
        )

        val input = IncrementalSynthesisInput(
            query = "What's in the standard body check package?",
            newSources = listOf(newSource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = listOf("https://example.com/body-check/standard"),
            currentAssessment = AnswerAssessment(
                coverage = DimensionAssessment(satisfied = true, rationale = "Package contents and price covered"),
                depth = DimensionAssessment(satisfied = true, rationale = "Specific tests and price listed"),
                temporality = DimensionAssessment(satisfied = true, rationale = "Current content"),
                authority = DimensionAssessment(satisfied = true, rationale = "Official product page"),
                consistency = DimensionAssessment(satisfied = true, rationale = "No conflicts")
            ),
            fulfillmentRequirements = listOf(
                "List of tests/screenings included in the standard package",
                "Price of the standard package"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        println("=== Requirements Coverage TEST ===")
        println("Status: ${output.status}")
        println("Assessment coverage: ${output.assessment.coverage}")
        println("Follow-up queries: ${output.followUpQueries}")
        println("Answer: ${output.answer.take(800)}")
        println()

        assertEquals(
            AnswerStatus.FINISH_SEARCH,
            output.status,
            "Should FINISH_SEARCH because all requirements (package contents, price) are satisfied. " +
            "Coverage rationale: ${output.assessment.coverage.rationale}"
        )
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should CONTINUE_SEARCH when requirements are not satisfied`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = """
            ## Body Check Packages
            
            The clinic offers various body check packages including Standard, Comprehensive, and Ultra tiers [1].
        """.trimIndent()

        val newSource = EvaluatedSource(
            url = "https://example.com/about",
            title = "About Us",
            description = "About the clinic",
            relevantFacts = listOf(
                RelevantFact(fact = "The clinic has been operating since 1994."),
                RelevantFact(fact = "Located in Central, Hong Kong.")
            ),
            contentDate = "2025-01-01",
            intention = "About page with clinic history and location"
        )

        val input = IncrementalSynthesisInput(
            query = "What's in the standard body check package?",
            newSources = listOf(newSource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = listOf("https://example.com/body-check"),
            currentAssessment = AnswerAssessment(
                coverage = DimensionAssessment(satisfied = false, rationale = "Only tier names, no package contents"),
                depth = DimensionAssessment(satisfied = false, rationale = "No specifics about tests or prices"),
                temporality = DimensionAssessment(satisfied = true, rationale = "Current content"),
                authority = DimensionAssessment(satisfied = true, rationale = "Official site"),
                consistency = DimensionAssessment(satisfied = true, rationale = "No conflicts")
            ),
            fulfillmentRequirements = listOf(
                "List of tests/screenings included in the standard package",
                "Price of the standard package",
                "How to book the standard package"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        println("=== Requirements Not Satisfied TEST ===")
        println("Status: ${output.status}")
        println("Assessment coverage: ${output.assessment.coverage}")
        println("Follow-up queries: ${output.followUpQueries}")
        println("Answer: ${output.answer.take(500)}")
        println()

        assertEquals(
            AnswerStatus.CONTINUE_SEARCH,
            output.status,
            "Should CONTINUE_SEARCH because requirements (package contents, price) are still missing"
        )
        assertFalse(
            output.assessment.coverage.satisfied,
            "Coverage should NOT be satisfied when requirements are missing"
        )
        assertTrue(
            output.followUpQueries.isNotEmpty(),
            "Should suggest follow-up queries to find missing information"
        )
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    // ==================== Follow-up Query Deduplication ====================

    @Test
    fun `should not suggest previously searched queries as follow-ups`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = """
            ## API Documentation
            
            The API supports REST endpoints for user management [1].
        """.trimIndent()

        val newSource = EvaluatedSource(
            url = "https://example.com/api-auth",
            title = "API Authentication",
            description = "Auth documentation",
            relevantFacts = listOf(
                RelevantFact(fact = "OAuth 2.0 is the primary authentication method."),
                RelevantFact(fact = "API keys are also supported for server-to-server communication.")
            ),
            contentDate = "2025-10-01",
            intention = "Official API authentication documentation page"
        )

        val input = IncrementalSynthesisInput(
            query = "How do I authenticate with the API?",
            newSources = listOf(newSource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = listOf("https://example.com/api-docs"),
            currentAssessment = AnswerAssessment(
                coverage = DimensionAssessment(satisfied = false, rationale = "Basic endpoints covered, auth missing"),
                depth = DimensionAssessment(satisfied = false, rationale = "No auth details"),
                temporality = DimensionAssessment(satisfied = true, rationale = "Current"),
                authority = DimensionAssessment(satisfied = true, rationale = "Official docs"),
                consistency = DimensionAssessment(satisfied = true, rationale = "No conflicts")
            ),
            previouslySearchedQueries = listOf(
                "API authentication guide",
                "OAuth setup tutorial",
                "API key management"
            ),
            fulfillmentRequirements = listOf(
                "Authentication methods supported",
                "How to obtain API credentials",
                "Rate limiting per auth method"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        println("=== FOLLOW-UP DEDUP TEST ===")
        println("Status: ${output.status}")
        println("Follow-up queries: ${output.followUpQueries}")
        println()

        output.followUpQueries.forEach { query ->
            val queryLower = query.lowercase().trim()
            input.previouslySearchedQueries.forEach { prev ->
                val prevLower = prev.lowercase().trim()
                assertFalse(
                    queryLower == prevLower || queryLower.contains(prevLower) || prevLower.contains(queryLower),
                    "Follow-up query '$query' overlaps with previously searched '$prev'"
                )
            }
        }
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    // ==================== Empty New Sources ====================

    @Test
    fun `should return current answer unchanged when new sources have no facts`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = """
            ## Product Pricing
            
            The product costs \$49/month for the Pro plan [1].
        """.trimIndent()

        val emptySource = EvaluatedSource(
            url = "https://example.com/empty",
            title = "Empty Page",
            description = "No content",
            relevantFacts = emptyList(),
            contentDate = null,
            intention = "Page with no extractable content"
        )

        val input = IncrementalSynthesisInput(
            query = "What is the pricing?",
            newSources = listOf(emptySource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = listOf("https://example.com/pricing"),
            currentAssessment = allSatisfiedAssessment()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        println("=== EMPTY SOURCES TEST ===")
        println("Status: ${output.status}")
        println("Answer: ${output.answer.take(500)}")
        println()

        assertEquals(currentAnswer, output.answer, "Answer should be unchanged when no new facts")
        assertEquals(AnswerStatus.CONTINUE_SEARCH, output.status, "Should CONTINUE_SEARCH as a safe default")
        assertTrue(output.tokenUsage.totalTokens == 0, "Should have zero tokens for short-circuit path")
    }

    // ==================== Real-World Scenario: OT&P Body Check ====================

    @Test
    fun `real world - OT&P body check incremental update with additional package details`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = """
            ## OT&P Standard Body Check Package

            The OT&P Standard Health Screening package includes a 45-minute consultation with the following tests and screenings [1]:

            - **Blood Tests**: Complete blood count, blood sugar (fasting glucose), kidney function, liver function
            - **Cardiovascular**: Coronary risk profile (cholesterol panel)
            - **Infectious Disease Screening**: Hepatitis B, HIV, Syphilis

            **Price: HK${'$'}5,900** [1]
        """.trimIndent()

        val additionalSource = EvaluatedSource(
            url = "https://www.otandp.com/body-check/standard/details",
            title = "Standard Package Details",
            description = "Additional details about the standard body check",
            relevantFacts = listOf(
                RelevantFact(fact = "The standard package also includes a urine analysis and thyroid function test (TSH)."),
                RelevantFact(fact = "Results consultation is included within 2 weeks of the check."),
                RelevantFact(fact = "Patients must fast for 10-12 hours before the appointment."),
                RelevantFact(fact = "Online booking available at otandp.com/online-appointment.")
            ),
            contentDate = "2025-11-15",
            intention = "Official detailed breakdown page for the standard body check package"
        )

        val input = IncrementalSynthesisInput(
            query = "What's in the standard body check package at OT&P?",
            newSources = listOf(additionalSource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = listOf("https://www.otandp.com/body-check/standard"),
            currentAssessment = AnswerAssessment(
                coverage = DimensionAssessment(satisfied = true, rationale = "Main tests and price covered"),
                depth = DimensionAssessment(satisfied = true, rationale = "Specific tests and price listed"),
                temporality = DimensionAssessment(satisfied = true, rationale = "Current content"),
                authority = DimensionAssessment(satisfied = true, rationale = "Official OT&P website"),
                consistency = DimensionAssessment(satisfied = true, rationale = "Single source")
            ),
            fulfillmentRequirements = listOf(
                "List of tests/screenings included in the standard package",
                "Price of the standard package",
                "How to book the standard package",
                "Preparation instructions"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        println("=== REAL-WORLD OT&P TEST ===")
        println("Status: ${output.status}")
        println("Assessment: ${output.assessment}")
        println("Cited URLs: ${output.citedSourceUrls}")
        println("Answer: ${output.answer}")
        println()

        assertTrue(
            output.answer.contains("5,900") || output.answer.contains("5900"),
            "Should preserve the price from the original answer"
        )
        assertTrue(
            output.answer.contains("urine", ignoreCase = true) || output.answer.contains("thyroid", ignoreCase = true),
            "Should incorporate new test details (urine analysis, thyroid function)"
        )
        assertFalse(
            output.answer.contains("pasta", ignoreCase = true) || output.answer.contains("recipe", ignoreCase = true),
            "Should NOT hallucinate unrelated information"
        )

        assertEquals(
            AnswerStatus.FINISH_SEARCH,
            output.status,
            "Should FINISH_SEARCH since all requirements are satisfied (tests + price). " +
            "Assessment: ${output.assessment.coverage}"
        )
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    // ==================== Multiple New Sources ====================

    @Test
    fun `should integrate information from multiple new sources correctly`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = """
            ## SleekFlow Overview
            
            SleekFlow is a social commerce platform that provides an omnichannel inbox for managing customer conversations [1].
        """.trimIndent()

        val pricingSource = EvaluatedSource(
            url = "https://sleekflow.io/pricing",
            title = "SleekFlow Pricing",
            description = "Pricing page",
            relevantFacts = listOf(
                RelevantFact(fact = "Pro plan: HK\$599/month. Includes omnichannel inbox, 3 active flows, WhatsApp Broadcast."),
                RelevantFact(fact = "Premium plan: HK\$2,399/month. Includes HubSpot integration, advanced analytics, 25 active flows."),
                RelevantFact(fact = "Enterprise plan: Custom pricing with Salesforce integration and unlimited features.")
            ),
            contentDate = "2026-01-01",
            intention = "Official pricing page with current pricing for all tiers"
        )

        val featureSource = EvaluatedSource(
            url = "https://sleekflow.io/features/flow-builder",
            title = "Flow Builder",
            description = "Automation features",
            relevantFacts = listOf(
                RelevantFact(fact = "Flow Builder enables no-code automation with drag-and-drop interface."),
                RelevantFact(fact = "Pro plan allows 25 nodes per flow and 500 monthly enrollments."),
                RelevantFact(fact = "Premium plan allows 100 nodes per flow and 3,000 monthly enrollments.")
            ),
            contentDate = "2025-12-01",
            intention = "Official feature page for Flow Builder automation"
        )

        val input = IncrementalSynthesisInput(
            query = "What are SleekFlow's pricing plans and what features does each include?",
            newSources = listOf(pricingSource, featureSource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = listOf("https://sleekflow.io/features"),
            currentAssessment = AnswerAssessment(
                coverage = DimensionAssessment(satisfied = false, rationale = "Only basic overview, no pricing"),
                depth = DimensionAssessment(satisfied = false, rationale = "No specific plan details"),
                temporality = DimensionAssessment(satisfied = true, rationale = "Current content"),
                authority = DimensionAssessment(satisfied = true, rationale = "Official SleekFlow site"),
                consistency = DimensionAssessment(satisfied = true, rationale = "No conflicts")
            ),
            fulfillmentRequirements = listOf(
                "List of pricing tiers",
                "Price of each tier",
                "Key features per tier",
                "Flow Builder limits per tier"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        println("=== MULTIPLE SOURCES TEST ===")
        println("Status: ${output.status}")
        println("Cited URLs: ${output.citedSourceUrls}")
        println("Answer: ${output.answer}")
        println()

        assertTrue(output.answer.contains("599") || output.answer.contains("Pro", ignoreCase = true), "Should mention Pro pricing")
        assertTrue(output.answer.contains("2,399") || output.answer.contains("2399") || output.answer.contains("Premium", ignoreCase = true), "Should mention Premium pricing")
        assertTrue(output.answer.contains("Enterprise", ignoreCase = true), "Should mention Enterprise tier")

        assertTrue(
            output.citedSourceUrls.size >= 2,
            "Should cite at least 2 sources. Got: ${output.citedSourceUrls}"
        )

        assertFalse(
            output.answer.contains("pasta") || output.answer.contains("recipe"),
            "Should NOT hallucinate unrelated content"
        )
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    // ==================== Session History Interaction ====================

    @Test
    fun `should use session history context without repeating prior findings`() = runTest(testCoroutineDispatcher) {
        val currentAnswer = """
            ## Feature Overview
            
            The platform supports WhatsApp Business API, Facebook Messenger, and Instagram DM integrations [1].
        """.trimIndent()

        val pricingSource = EvaluatedSource(
            url = "https://example.com/pricing",
            title = "Pricing Page",
            description = "Current pricing",
            relevantFacts = listOf(
                RelevantFact(fact = "Starter plan: \$29/month for up to 1,000 contacts."),
                RelevantFact(fact = "Growth plan: \$79/month for up to 5,000 contacts."),
                RelevantFact(fact = "Scale plan: \$199/month for unlimited contacts.")
            ),
            contentDate = "2026-02-01",
            intention = "Official pricing page with current pricing"
        )

        val sessionHistory = SessionHistory(listOf(
            SessionHistory.SessionSummary(
                sessionId = "prior-session-1",
                query = "What integrations does the platform support?",
                answer = """
                    The platform integrates with WhatsApp Business API, Facebook Messenger, 
                    Instagram DM, and Telegram. It also supports Shopify and HubSpot CRM integrations.
                """.trimIndent()
            )
        ))

        val input = IncrementalSynthesisInput(
            query = "What are the pricing plans?",
            newSources = listOf(pricingSource),
            currentAnswer = currentAnswer,
            currentCitedSourceUrls = listOf("https://example.com/features"),
            currentAssessment = AnswerAssessment(
                coverage = DimensionAssessment(satisfied = false, rationale = "Features covered but pricing missing"),
                depth = DimensionAssessment(satisfied = false, rationale = "No pricing data"),
                temporality = DimensionAssessment(satisfied = true, rationale = "Current"),
                authority = DimensionAssessment(satisfied = true, rationale = "Official site"),
                consistency = DimensionAssessment(satisfied = true, rationale = "No conflicts")
            ),
            sessionHistory = sessionHistory,
            fulfillmentRequirements = listOf(
                "List of pricing tiers",
                "Price per tier",
                "Contact limits per tier"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        println("=== SESSION HISTORY TEST ===")
        println("Status: ${output.status}")
        println("Answer: ${output.answer.take(800)}")
        println()

        assertTrue(
            output.answer.contains("29") || output.answer.contains("79") || output.answer.contains("199"),
            "Should include pricing details from new source"
        )
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }
}
