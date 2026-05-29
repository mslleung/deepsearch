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
 * Integration tests against https://www.notion.com/pricing to validate the VLM
 * agent's ability to navigate a rich comparison grid page with plan cards,
 * deep feature comparison tables, and FAQ accordions.
 *
 * Tests cover:
 * - Reading plan card prices (visible near top)
 * - Navigating deep comparison grid sections (Content, Sharing, AI, Admin)
 * - Expanding FAQ accordions (student discount, refund policy, payment fails)
 * - Negative cases for non-existent features
 *
 * Requires deepsearch-browser running at localhost:8090 and GOOGLE_API_KEY env var.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class NotionPricingSearchTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    companion object {
        private const val PRICING_URL = "https://www.notion.com/pricing"
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
            sessionId = QuerySessionId("notion-$testName")
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
            sessionId = QuerySessionId("notion-neg-$testName")
        )
        val latency = System.currentTimeMillis() - start
        printResult(result, latency)

        assertTrue(!result.success || result.answer == null,
            "Negative test should not succeed with an answer: ${result.answer}")
    }

    // ==================== Group 1: Plan Pricing ====================

    @Test
    fun `plan price - Plus`() = runBlocking {
        runTest("plus-price", "What is the price of the Notion Plus plan per member per month?") { answer ->
            assertTrue(answer.contains("10"), "Expected $10: $answer")
        }
    }

    @Test
    fun `plan price - Business`() = runBlocking {
        runTest("business-price", "What is the price of the Notion Business plan per member per month?") { answer ->
            assertTrue(answer.contains("20"), "Expected $20: $answer")
        }
    }

    @Test
    fun `plan price - Custom Agents`() = runBlocking {
        runTest("custom-agents-price", "What is the price for Notion Custom Agents after the free trial?") { answer ->
            assertTrue(answer.contains("10") && answer.contains("1,000"), "Expected $10 per 1,000 credits: $answer")
        }
    }

    // ==================== Group 2: Comparison Grid ====================

    @Test
    fun `grid - file upload limit Free`() = runBlocking {
        runTest("file-upload-free", "What is the maximum file upload size on Notion's Free plan?") { answer ->
            assertTrue(answer.contains("5"), "Expected 5 MB: $answer")
        }
    }

    @Test
    fun `grid - page history Plus`() = runBlocking {
        runTest("page-history-plus", "How many days of page history does the Notion Plus plan include?") { answer ->
            assertTrue(answer.contains("30"), "Expected 30 days: $answer")
        }
    }

    @Test
    fun `grid - guest limit Free`() = runBlocking {
        runTest("guest-limit-free", "How many external guests can you invite on Notion's Free plan?") { answer ->
            assertTrue(answer.contains("10"), "Expected 10: $answer")
        }
    }

    @Test
    fun `grid - SAML SSO plans`() = runBlocking {
        runTest("saml-sso", "Which Notion plans include SAML single sign-on (SSO)?") { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("business") && lower.contains("enterprise"), "Expected Business + Enterprise: $answer")
        }
    }

    @Test
    fun `grid - AI data retention`() = runBlocking {
        runTest("ai-data-retention", "What is the Notion AI data retention policy for Enterprise vs Business?") { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("zero") && lower.contains("30"), "Expected zero + 30 day: $answer")
        }
    }

    @Test
    fun `grid - charts Free limit`() = runBlocking {
        runTest("charts-free-limit", "How many charts can you create on Notion's Free plan?") { answer ->
            assertTrue(answer.contains("1"), "Expected 1: $answer")
        }
    }

    // ==================== Group 3: FAQ Accordion ====================

    @Test
    fun `faq - payment fails retry`() = runBlocking {
        runTest("faq-payment-fails", "According to Notion's FAQ, how many times may a failed payment be retried?") { answer ->
            assertTrue(answer.contains("8"), "Expected 8: $answer")
        }
    }

    @Test
    fun `faq - student discount`() = runBlocking {
        runTest("faq-student", "According to Notion's FAQ, what discount do students get?") { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("free") && lower.contains("student"), "Expected free for students: $answer")
        }
    }

    @Test
    fun `faq - refund policy`() = runBlocking {
        runTest("faq-refund", "According to Notion's FAQ, within how many days can you get a refund for monthly billing?") { answer ->
            assertTrue(answer.contains("three") || answer.contains("3"), "Expected 3 days: $answer")
        }
    }

    // ==================== Group 4: Negative Cases ====================

    @Test
    fun `negative - phone support`() = runBlocking {
        runNegativeTest("phone-support", "What is Notion's phone support number and what hours is it available?")
    }

    @Test
    fun `negative - video conferencing`() = runBlocking {
        runNegativeTest("video-conferencing", "Which Notion plans include built-in video conferencing with screen sharing?")
    }

    @Test
    fun `negative - lifetime plan`() = runBlocking {
        runNegativeTest("lifetime-plan", "What is the price of Notion's lifetime plan with a one-time payment?")
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
