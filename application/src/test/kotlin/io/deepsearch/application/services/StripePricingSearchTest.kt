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
 * Integration tests against Stripe pricing to validate the VLM agent's ability
 * to navigate a long, section-rich pricing page with expandable "More features"
 * sections, FAQ accordions, and multiple product categories.
 *
 * Uses /us/ locale to pin US pricing regardless of VPN geo-IP location.
 * Using /en-us/ only pins the language, not the pricing region.
 *
 * Tests cover:
 * - Reading headline pricing near the top (domestic card rate, international surcharge)
 * - Scrolling to mid-page product sections (Terminal, ACH)
 * - Expanding hidden sections ("More features", FAQ accordions)
 * - Negative cases for non-existent features/services
 *
 * Requires deepsearch-browser running at localhost:8090 and GOOGLE_API_KEY env var.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class StripePricingSearchTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    companion object {
        private const val PRICING_URL = "https://stripe.com/us/pricing"
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
            sessionId = QuerySessionId("stripe-$testName")
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
            sessionId = QuerySessionId("stripe-neg-$testName")
        )
        val latency = System.currentTimeMillis() - start
        printResult(result, latency)

        assertTrue(!result.success || result.answer == null,
            "Negative test should not succeed with an answer: ${result.answer}")
    }

    // ==================== Group 1: Headline Pricing (near top) ====================

    @Test
    fun `pricing - domestic card rate`() = runBlocking {
        runTest("domestic-card-rate", "What is Stripe's standard processing fee for domestic card transactions?") { answer ->
            assertTrue(answer.contains("2.9") && answer.contains("30"), "Expected 2.9% + 30¢: $answer")
        }
    }

    @Test
    fun `pricing - international card surcharge`() = runBlocking {
        runTest("international-surcharge", "What additional fee does Stripe charge for international card transactions?") { answer ->
            assertTrue(answer.contains("1.5"), "Expected 1.5%: $answer")
        }
    }

    @Test
    fun `pricing - ACH Direct Debit rate`() = runBlocking {
        runTest("ach-rate", "What is Stripe's fee for ACH Direct Debit transactions and what is the cap?") { answer ->
            assertTrue(answer.contains("0.8") && answer.contains("5"), "Expected 0.8% with $5 cap: $answer")
        }
    }

    @Test
    fun `pricing - Terminal domestic card rate`() = runBlocking {
        runTest("terminal-rate", "What is Stripe Terminal's processing fee for in-person domestic card transactions?") { answer ->
            assertTrue(answer.contains("2.7") && answer.contains("5"), "Expected 2.7% + 5¢: $answer")
        }
    }

    // ==================== Group 2: Expandable Sections ====================

    @Test
    fun `expand - Billing usage-based rate`() = runBlocking {
        runTest("billing-usage", "What is Stripe's usage-based Billing fee as a percentage of volume?") { answer ->
            assertTrue(answer.contains("0.7"), "Expected 0.7%: $answer")
        }
    }

    @Test
    fun `expand - Tax API per-transaction price`() = runBlocking {
        runTest("tax-api", "What does Stripe Tax charge per transaction for API integrations?") { answer ->
            assertTrue(answer.contains("0.50") || answer.contains("50"), "Expected $0.50: $answer")
        }
    }

    @Test
    fun `expand - Radar Fraud Teams custom pricing`() = runBlocking {
        runTest("radar-fraud-custom", "What does Stripe Radar for Fraud Teams charge per screened transaction for accounts with custom pricing?") { answer ->
            assertTrue(answer.contains("0.07") || answer.contains("7"), "Expected $0.07: $answer")
        }
    }

    @Test
    fun `expand - 3D Secure in More features`() = runBlocking {
        runTest("3d-secure", "Is 3D Secure authentication available on Stripe's standard pricing page under Payments?") { answer ->
            assertTrue(answer.lowercase().contains("3d secure"), "Expected 3D Secure mention: $answer")
        }
    }

    // ==================== Group 3: FAQ Accordion ====================

    @Test
    fun `faq - setup fees`() = runBlocking {
        runTest("faq-setup-fees", "According to Stripe's FAQ, does Stripe charge setup fees, monthly fees, or closure fees?") { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("does not") || lower.contains("no"), "Expected no fees: $answer")
        }
    }

    @Test
    fun `faq - refund fees standard`() = runBlocking {
        runTest("faq-refund", "According to Stripe's FAQ, are there fees for refunds on standard pricing?") { answer ->
            assertTrue(answer.lowercase().contains("refund"), "Expected refund info: $answer")
        }
    }

    @Test
    fun `faq - volume discounts`() = runBlocking {
        runTest("faq-discounts", "According to Stripe's FAQ, does Stripe offer discounts for large processing volumes?") { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("custom") || lower.contains("volume"), "Expected custom pricing mention: $answer")
        }
    }

    // ==================== Group 4: Deep Scroll ====================

    @Test
    fun `deep - Atlas incorporation fee`() = runBlocking {
        runTest("atlas-fee", "What is the one-time setup fee for Stripe Atlas incorporation?") { answer ->
            assertTrue(answer.contains("500"), "Expected $500: $answer")
        }
    }

    @Test
    fun `deep - Issuing virtual card price`() = runBlocking {
        runTest("issuing-virtual", "How much does Stripe charge per virtual card issued?") { answer ->
            assertTrue(answer.contains("0.10") || answer.contains("10"), "Expected $0.10: $answer")
        }
    }

    // ==================== Group 5: Negative Cases ====================

    @Test
    fun `negative - free plan`() = runBlocking {
        runNegativeTest("free-plan", "What is included in Stripe's free plan and how many free transactions per month does it offer?")
    }

    @Test
    fun `negative - phone support`() = runBlocking {
        runNegativeTest("phone-support", "What is the phone number for Stripe standard-pricing customer support?")
    }

    @Test
    fun `negative - escrow service`() = runBlocking {
        runNegativeTest("escrow", "What is the fee for Stripe's escrow service that holds funds between buyer and seller?")
    }

    @Test
    fun `negative - chargeback insurance`() = runBlocking {
        runNegativeTest("chargeback-insurance", "How much does Stripe's chargeback insurance cost to guarantee against all dispute losses?")
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
