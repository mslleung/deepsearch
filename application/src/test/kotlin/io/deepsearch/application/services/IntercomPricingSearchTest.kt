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
 * Integration tests against https://www.intercom.com/pricing to validate the VLM
 * agent's ability to navigate a SaaS pricing page with a different model than
 * pure tiered pricing:
 * - Seat-based + usage-based (per Fin outcome) hybrid model
 * - Three plan tiers (Essential, Advanced, Expert)
 * - Add-ons section (Pro, Copilot, Proactive Support Plus)
 * - FAQ accordions with collapsed multi-section answers
 * - Plan feature bullets with specific inclusions (Lite seats, SLAs, HIPAA)
 *
 * Requires deepsearch-browser running at localhost:8090 and GOOGLE_API_KEY env var.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class IntercomPricingSearchTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    companion object {
        private const val PRICING_URL = "https://www.intercom.com/pricing"
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
            sessionId = QuerySessionId("intercom-$testName")
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
            sessionId = QuerySessionId("intercom-neg-$testName")
        )
        val latency = System.currentTimeMillis() - start
        printResult(result, latency)

        assertTrue(!result.success || result.answer == null,
            "Negative test should not succeed with an answer: ${result.answer}")
    }

    // ==================== Group 1: Plan Seat Pricing ====================

    @Test
    fun `plan price - Essential seat`() = runBlocking {
        runTest("essential-seat", "What is the Intercom Essential plan price per seat per month?") { answer ->
            assertTrue(answer.contains("29"), "Expected $29: $answer")
        }
    }

    @Test
    fun `plan price - Advanced seat`() = runBlocking {
        runTest("advanced-seat", "What is the Intercom Advanced plan price per seat per month?") { answer ->
            assertTrue(answer.contains("85"), "Expected $85: $answer")
        }
    }

    @Test
    fun `plan price - Expert seat`() = runBlocking {
        runTest("expert-seat", "What is the Intercom Expert plan price per seat per month?") { answer ->
            assertTrue(answer.contains("132"), "Expected $132: $answer")
        }
    }

    @Test
    fun `plan price - Fin AI outcome`() = runBlocking {
        runTest("fin-outcome", "How much does Intercom charge per Fin AI Agent outcome?") { answer ->
            assertTrue(answer.contains("0.99"), "Expected $0.99: $answer")
        }
    }

    // ==================== Group 2: Plan Features ====================

    @Test
    fun `features - Advanced Lite seats`() = runBlocking {
        runTest("advanced-lite-seats", "How many free Lite seats are included with the Intercom Advanced plan?") { answer ->
            assertTrue(answer.contains("20"), "Expected 20: $answer")
        }
    }

    @Test
    fun `features - Expert SLA`() = runBlocking {
        runTest("expert-sla", "Which Intercom plan includes Service Level Agreements (SLAs)?") { answer ->
            assertTrue(answer.lowercase().contains("expert"), "Expected Expert: $answer")
        }
    }

    @Test
    fun `features - Expert HIPAA`() = runBlocking {
        runTest("expert-hipaa", "Which Intercom plan includes HIPAA compliance support?") { answer ->
            assertTrue(answer.lowercase().contains("expert"), "Expected Expert: $answer")
        }
    }

    @Test
    fun `features - Expert Lite seats`() = runBlocking {
        runTest("expert-lite-seats", "How many free Lite seats are included with the Intercom Expert plan?") { answer ->
            assertTrue(answer.contains("50"), "Expected 50: $answer")
        }
    }

    // ==================== Group 3: Add-ons ====================

    @Test
    fun `addon - Pro price and conversations`() = runBlocking {
        runTest("pro-addon", "What is the price of Intercom's Pro add-on and how many conversations does it include?") { answer ->
            assertTrue(answer.contains("99") && answer.contains("1,000"), "Expected $99 and 1,000 conversations: $answer")
        }
    }

    @Test
    fun `addon - Copilot per agent`() = runBlocking {
        runTest("copilot-addon", "What is the per-agent monthly price of the Intercom Copilot add-on?") { answer ->
            assertTrue(answer.contains("29"), "Expected $29: $answer")
        }
    }

    @Test
    fun `addon - Proactive Support Plus`() = runBlocking {
        runTest("proactive-support", "What is Intercom's Proactive Support Plus add-on price and how many messages does it include?") { answer ->
            assertTrue(answer.contains("99") && answer.contains("500"), "Expected $99 and 500 messages: $answer")
        }
    }

    // ==================== Group 4: FAQ Accordion ====================

    @Test
    fun `faq - pricing components`() = runBlocking {
        runTest("faq-pricing", "According to Intercom's FAQ, what are the two main components of Intercom pricing?") { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("seat") && lower.contains("usage"), "Expected seats + usage: $answer")
        }
    }

    @Test
    fun `faq - minimum to get started`() = runBlocking {
        runTest("faq-minimum", "According to Intercom's FAQ, what is the minimum required to get started with Intercom?") { answer ->
            assertTrue(answer.lowercase().contains("seat"), "Expected seat mention: $answer")
        }
    }

    // ==================== Group 5: Negative Cases ====================

    @Test
    fun `negative - phone support number`() = runBlocking {
        runNegativeTest("phone-support", "What is Intercom's direct phone support number for customers?")
    }

    @Test
    fun `negative - API rate limits`() = runBlocking {
        runNegativeTest("api-rate-limits", "What are the API rate limits per plan on Intercom's pricing page?")
    }

    @Test
    fun `negative - free plan`() = runBlocking {
        runNegativeTest("free-plan", "What features are included in Intercom's free plan with no payment required?")
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
