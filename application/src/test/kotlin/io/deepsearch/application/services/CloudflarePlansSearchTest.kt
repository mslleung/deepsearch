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
 * Integration tests against https://www.cloudflare.com/plans/ to validate
 * the VLM agent's ability to navigate a multi-tab pricing page with:
 * - Plan comparison cards (Free, Pro, Business, Enterprise)
 * - "Compare all features" scrollable overlay
 * - Developer Platform tabs with nested sub-tabs (Workers, KV, Pages, R2, D1)
 * - FAQ accordions
 *
 * Requires deepsearch-browser running at localhost:8090 and GOOGLE_API_KEY env var.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class CloudflarePlansSearchTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    companion object {
        private const val PLANS_URL = "https://www.cloudflare.com/plans/"
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
        println("URL: $PLANS_URL")
        println("QUERY: $query")
        println("==================================================\n")

        val start = System.currentTimeMillis()
        val result = agenticSearchService.searchWithinPage(
            url = PLANS_URL,
            query = query,
            sessionId = QuerySessionId("cf-$testName")
        )
        val latency = System.currentTimeMillis() - start
        printResult(result, latency)

        assertNotNull(result.answer, "Answer should not be null")
        expectedAnswerCheck(result.answer!!)
    }

    private suspend fun runNegativeTest(testName: String, query: String) {
        println("\n==================================================")
        println("TEST (negative): $testName")
        println("URL: $PLANS_URL")
        println("QUERY: $query")
        println("==================================================\n")

        val start = System.currentTimeMillis()
        val result = agenticSearchService.searchWithinPage(
            url = PLANS_URL,
            query = query,
            sessionId = QuerySessionId("cf-neg-$testName")
        )
        val latency = System.currentTimeMillis() - start
        printResult(result, latency)

        assertTrue(!result.success || result.answer == null,
            "Negative test should not succeed with an answer: ${result.answer}")
    }

    // ==================== Group 1: Plan Card Pricing ====================

    @Test
    fun `plan price - Free`() = runBlocking {
        runTest("free-price", "What does the Cloudflare Free plan cost per month?") { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("0") || lower.contains("free"), "Expected free/$0: $answer")
        }
    }

    @Test
    fun `plan price - Business`() = runBlocking {
        runTest("business-price", "What is the Cloudflare Business plan price per month?") { answer ->
            assertTrue(answer.contains("200"), "Expected $200: $answer")
        }
    }

    @Test
    fun `plan price - Pro monthly`() = runBlocking {
        runTest("pro-monthly", "What is the Cloudflare Pro plan price per month if billed monthly?") { answer ->
            assertTrue(answer.contains("25"), "Expected $25: $answer")
        }
    }

    // ==================== Group 2: Compare Overlay ====================

    @Test
    fun `compare - Client Max Upload Size Business`() = runBlocking {
        runTest("upload-size-business", "In Cloudflare's full feature comparison, what is the Client Max Upload Size for the Business plan?") { answer ->
            assertTrue(answer.contains("200"), "Expected 200 MB: $answer")
        }
    }

    @Test
    fun `compare - WAF custom rules Pro`() = runBlocking {
        runTest("waf-rules-pro", "In Cloudflare's full feature comparison, how many custom WAF rules does the Pro plan include?") { answer ->
            assertTrue(answer.contains("5"), "Expected 5: $answer")
        }
    }

    // ==================== Group 3: Developer Platform ====================

    @Test
    fun `dev platform - Workers KV free reads`() = runBlocking {
        runTest("kv-reads", "On Cloudflare's Developer Platform, how many Workers KV reads per day are included free?") { answer ->
            assertTrue(answer.contains("100"), "Expected 100K: $answer")
        }
    }

    @Test
    fun `dev platform - Workers overage`() = runBlocking {
        runTest("workers-overage", "What is the overage charge per million requests for Cloudflare Workers Paid?") { answer ->
            assertTrue(answer.contains("0.30") || answer.contains("0.3"), "Expected $0.30: $answer")
        }
    }

    @Test
    fun `dev platform - R2 free storage`() = runBlocking {
        runTest("r2-storage", "How much free storage per month does Cloudflare R2 include on the free tier?") { answer ->
            assertTrue(answer.contains("10"), "Expected 10 GB: $answer")
        }
    }

    @Test
    fun `dev platform - Pages concurrent builds`() = runBlocking {
        runTest("pages-builds", "How many concurrent builds does the Cloudflare Pages free tier include?") { answer ->
            assertTrue(answer.contains("1"), "Expected 1: $answer")
        }
    }

    // ==================== Group 4: FAQ ====================

    @Test
    fun `faq - billing date`() = runBlocking {
        runTest("faq-billing", "According to Cloudflare's FAQ, if you sign up on January 10, when are future charges billed?") { answer ->
            assertTrue(answer.contains("10"), "Expected 10th: $answer")
        }
    }

    @Test
    fun `faq - payment methods`() = runBlocking {
        runTest("faq-payment", "According to Cloudflare's FAQ, what payment methods are accepted?") { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("credit") || lower.contains("paypal"), "Expected credit card or PayPal: $answer")
        }
    }

    // ==================== Group 5: Negative Cases ====================

    @Test
    fun `negative - video streaming`() = runBlocking {
        runNegativeTest("video-streaming", "What is the per-minute cost for Cloudflare Stream video encoding and delivery?")
    }

    @Test
    fun `negative - phone support Pro`() = runBlocking {
        runNegativeTest("phone-support", "What is the phone support number for Cloudflare Pro plan customers?")
    }

    @Test
    fun `negative - email hosting`() = runBlocking {
        runNegativeTest("email-hosting", "How much does Cloudflare email hosting cost per mailbox per month?")
    }

    @Test
    fun `negative - dedicated IP`() = runBlocking {
        runNegativeTest("dedicated-ip", "What is the monthly cost for a dedicated IP address on Cloudflare?")
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
