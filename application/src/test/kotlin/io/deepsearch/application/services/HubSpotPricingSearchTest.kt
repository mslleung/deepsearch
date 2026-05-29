package io.deepsearch.application.services

import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import io.deepsearch.domain.testing.IsolatedKoinTest
import org.koin.java.KoinJavaComponent.inject

/**
 * Integration tests against https://www.hubspot.com/pricing/marketing to validate
 * the VLM agent's ability to navigate HubSpot's Marketing Hub pricing page with:
 * - Tiered plan cards (Starter, Professional, Enterprise)
 * - AEO spotlight section with fine print
 * - FAQ accordions with multi-bullet answers
 * - Footnotes and asterisk disclaimers
 *
 * Requires deepsearch-browser running at localhost:8090 and GOOGLE_API_KEY env var.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class HubSpotPricingSearchTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    companion object {
        private const val PRICING_URL = "https://www.hubspot.com/pricing/marketing"
    }

    @BeforeAll
    fun setup() {
        koinApp = koinApplication {
            modules(applicationBenchmarkTestModule)
        }
        koinApp.createEagerInstances()
        testKoin = koinApp.koin
    }

    @AfterAll
    fun teardown() {
        applicationScope.close()
        koinApp.close()
    }

    private suspend fun runTest(
        testName: String,
        query: String,
        expectedAnswerCheck: (String) -> Unit
    ) {
        println("\n==================================================")
        println("TEST: $testName")
        println("URL: $PRICING_URL")
        println("QUERY: $query")
        println("==================================================\n")

        val start = System.currentTimeMillis()
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = query,
            sessionId = QuerySessionId("hubspot-$testName")
        )
        val latency = System.currentTimeMillis() - start
        printResult(result, latency)

        assertNotNull(result.answer, "Answer should not be null")
        expectedAnswerCheck(result.answer!!)
    }

    private suspend fun runNegativeTest(testName: String, query: String) {
        println("\n==================================================")
        println("TEST (negative): $testName")
        println("URL: $PRICING_URL")
        println("QUERY: $query")
        println("==================================================\n")

        val start = System.currentTimeMillis()
        val result = agenticSearchService.searchWithinPage(
            url = PRICING_URL,
            query = query,
            sessionId = QuerySessionId("hubspot-neg-$testName")
        )
        val latency = System.currentTimeMillis() - start
        printResult(result, latency)

        assertTrue(!result.success || result.answer == null,
            "Negative test should not succeed with an answer: ${result.answer}")
    }

    // ==================== Group 1: Plan Pricing ====================

    @Test
    fun `plan price - Starter`() = runBlocking {
        runTest("starter-price", "What is the starting price for HubSpot Marketing Hub Starter?") { answer ->
            assertTrue(answer.contains("20"), "Expected $20: $answer")
        }
    }

    @Test
    fun `plan price - Professional`() = runBlocking {
        runTest("professional-price", "What is the starting price for HubSpot Marketing Hub Professional?") { answer ->
            assertTrue(answer.contains("890"), "Expected $890: $answer")
        }
    }

    @Test
    fun `plan - free tools`() = runBlocking {
        runTest("free-tools", "Does HubSpot offer any free marketing tools without a paid plan?") { answer ->
            assertTrue(answer.lowercase().contains("free"), "Expected free tools mention: $answer")
        }
    }

    // ==================== Group 2: AEO Section ====================

    @Test
    fun `aeo - trial prompts`() = runBlocking {
        runTest("aeo-trial", "During the HubSpot AEO trial, how many prompts are tracked in ChatGPT?") { answer ->
            assertTrue(answer.contains("10"), "Expected 10: $answer")
        }
    }

    @Test
    fun `aeo - included tiers`() = runBlocking {
        runTest("aeo-tiers", "Beyond standalone purchase, which HubSpot Marketing Hub editions include AEO?") { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("professional") && lower.contains("enterprise"), "Expected Professional + Enterprise: $answer")
        }
    }

    // ==================== Group 3: FAQ Accordion ====================

    @Test
    fun `faq - email send limit Professional`() = runBlocking {
        runTest("faq-email-limit", "For HubSpot Marketing Hub Professional, what is the monthly email send limit relative to the contact tier?") { answer ->
            assertTrue(answer.lowercase().contains("10"), "Expected 10 times: $answer")
        }
    }

    @Test
    fun `faq - Enterprise marketing contacts`() = runBlocking {
        runTest("faq-contacts", "How many marketing contacts are included with HubSpot Marketing Hub Enterprise?") { answer ->
            assertTrue(answer.contains("10,000") || answer.contains("10000"), "Expected 10,000: $answer")
        }
    }

    @Test
    fun `faq - annual discount`() = runBlocking {
        runTest("faq-annual", "According to HubSpot's FAQ, do you get a discount for committing to an annual plan?") { answer ->
            assertTrue(answer.lowercase().contains("annual"), "Expected annual mention: $answer")
        }
    }

    @Test
    fun `faq - CRM included`() = runBlocking {
        runTest("faq-crm", "According to HubSpot's FAQ, is the CRM included with Marketing Hub?") { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("crm") && lower.contains("include"), "Expected CRM included: $answer")
        }
    }

    // ==================== Group 4: Footnotes ====================

    @Test
    fun `footnote - onboarding fee`() = runBlocking {
        runTest("onboarding-fee", "What is the required one-time Professional Onboarding fee for HubSpot Marketing Hub?") { answer ->
            assertTrue(answer.lowercase().contains("onboarding"), "Expected onboarding mention: $answer")
        }
    }

    // ==================== Group 5: Negative Cases ====================

    @Test
    fun `negative - phone support number`() = runBlocking {
        runNegativeTest("phone-support", "What is HubSpot's direct phone support number for Marketing Hub customers?")
    }

    @Test
    fun `negative - free trial days`() = runBlocking {
        runNegativeTest("trial-days", "Exactly how many days is the HubSpot Marketing Hub Professional free trial?")
    }

    @Test
    fun `negative - social scheduling limit`() = runBlocking {
        runNegativeTest("social-limit", "How many social media posts per month can be scheduled with HubSpot Marketing Hub Professional?")
    }

    @Test
    fun `negative - Salesforce connector`() = runBlocking {
        runNegativeTest("salesforce", "What is the price of HubSpot's native Salesforce integration add-on for Marketing Hub?")
    }

    // ==================== Helpers ====================

    private fun printResult(result: AgenticPageSearchResult, latencyMs: Long) {
        println("Success: ${result.success}")
        println("Latency: ${latencyMs}ms")
        println("Answer: ${result.answer}")
        println("Evidence: ${result.evidence}")
        println("Actions (${result.actionsPerformed.size}):")
        result.actionsPerformed.forEachIndexed { idx, action ->
            println("  ${idx + 1}. $action")
        }
        val clickCount = result.actionsPerformed.count { it.action is NavigationAction.Click }
        val scrollAtCount = result.actionsPerformed.count { it.action is NavigationAction.ScrollAt }
        println("Clicks: $clickCount, ScrollAt: $scrollAtCount")
        println("Token usage: prompt=${result.totalTokenUsage.promptTokens}, " +
                "output=${result.totalTokenUsage.outputTokens}, " +
                "total=${result.totalTokenUsage.totalTokens}")
    }
}
