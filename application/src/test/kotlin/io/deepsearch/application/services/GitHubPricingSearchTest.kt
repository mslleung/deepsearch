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
 * Integration tests against https://github.com/pricing to validate the VLM
 * agent's ability to navigate GitHub's pricing page with:
 * - Plan cards (Free, Team, Enterprise)
 * - "Compare all features" deep comparison table with expandable rows
 * - Add-on sections (Copilot, LFS, Codespaces, Advanced Security)
 * - Anchor-jump navigation between sections
 *
 * Requires deepsearch-browser running at localhost:8090 and GOOGLE_API_KEY env var.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class GitHubPricingSearchTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    companion object {
        private const val PRICING_URL = "https://github.com/pricing"
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
            sessionId = QuerySessionId("gh-$testName")
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
            sessionId = QuerySessionId("gh-neg-$testName")
        )
        val latency = System.currentTimeMillis() - start
        printResult(result, latency)

        assertTrue(!result.success || result.answer == null,
            "Negative test should not succeed with an answer: ${result.answer}")
    }

    // ==================== Group 1: Plan Pricing ====================

    @Test
    fun `plan price - Free`() = runBlocking {
        runTest("free-price", "What does the GitHub Free plan cost per month?") { answer ->
            assertTrue(answer.contains("0"), "Expected $0: $answer")
        }
    }

    @Test
    fun `plan price - Team`() = runBlocking {
        runTest("team-price", "What is the GitHub Team plan price per user per month?") { answer ->
            assertTrue(answer.contains("4"), "Expected $4: $answer")
        }
    }

    @Test
    fun `plan price - Enterprise`() = runBlocking {
        runTest("enterprise-price", "What is the starting price for GitHub Enterprise per user per month?") { answer ->
            assertTrue(answer.contains("21"), "Expected $21: $answer")
        }
    }

    // ==================== Group 2: Plan Features ====================

    @Test
    fun `features - Team Actions minutes`() = runBlocking {
        runTest("actions-minutes-team", "How many GitHub Actions minutes per month does the Team plan include?") { answer ->
            assertTrue(answer.contains("3,000") || answer.contains("3000"), "Expected 3,000: $answer")
        }
    }

    @Test
    fun `features - Packages storage Team`() = runBlocking {
        runTest("packages-team", "How much Packages storage does the GitHub Team plan include?") { answer ->
            assertTrue(answer.contains("2"), "Expected 2GB: $answer")
        }
    }

    @Test
    fun `features - Enterprise Actions minutes`() = runBlocking {
        runTest("actions-enterprise", "How many GitHub Actions minutes per month does the Enterprise plan include?") { answer ->
            assertTrue(answer.contains("50,000") || answer.contains("50000"), "Expected 50,000: $answer")
        }
    }

    // ==================== Group 3: Comparison Table / Expandable ====================

    @Test
    fun `compare - SAML SSO availability`() = runBlocking {
        runTest("saml-sso", "Which GitHub plans include SAML single sign-on (SSO)?") { answer ->
            assertTrue(answer.lowercase().contains("enterprise"), "Expected Enterprise: $answer")
        }
    }

    @Test
    fun `compare - Codespaces 2-core free hours`() = runBlocking {
        runTest("codespaces-2core", "How many free hours does GitHub Codespaces give on a 2-core machine?") { answer ->
            assertTrue(answer.contains("60"), "Expected 60: $answer")
        }
    }

    @Test
    fun `compare - Data residency cloud provider`() = runBlocking {
        runTest("data-residency", "Which cloud provider does GitHub Enterprise Cloud use for data residency?") { answer ->
            val lower = answer.lowercase()
            assertTrue(lower.contains("microsoft") || lower.contains("azure"), "Expected Microsoft Azure: $answer")
        }
    }

    // ==================== Group 4: Add-ons ====================

    @Test
    fun `addon - Copilot free limits`() = runBlocking {
        runTest("copilot-free", "How many free completions and chat requests per month does GitHub Copilot offer?") { answer ->
            assertTrue(answer.contains("2,000") || answer.contains("2000"), "Expected 2,000: $answer")
            assertTrue(answer.contains("50"), "Expected 50 chat requests: $answer")
        }
    }

    @Test
    fun `addon - Git LFS price`() = runBlocking {
        runTest("lfs-price", "How much does Git Large File Storage cost per month on GitHub?") { answer ->
            assertTrue(answer.contains("5"), "Expected $5: $answer")
        }
    }

    @Test
    fun `addon - Premium Support SLA`() = runBlocking {
        runTest("premium-sla", "What is the SLA response time for urgent tickets with GitHub Premium Support?") { answer ->
            assertTrue(answer.contains("30"), "Expected 30-minute SLA: $answer")
        }
    }

    // ==================== Group 5: Negative Cases ====================

    @Test
    fun `negative - on-premise price`() = runBlocking {
        runNegativeTest("on-premise", "What is the per-user price for GitHub Enterprise Server (self-hosted/on-premise)?")
    }

    @Test
    fun `negative - phone support Team`() = runBlocking {
        runNegativeTest("phone-support-team", "What is the phone number for GitHub Team plan phone support?")
    }

    @Test
    fun `negative - BitBucket migration`() = runBlocking {
        runNegativeTest("bitbucket-migration", "What does GitHub charge for the automated BitBucket repository migration tool?")
    }

    @Test
    fun `negative - AI code review addon`() = runBlocking {
        runNegativeTest("ai-code-review", "How much does GitHub's dedicated AI Code Review add-on cost per repository per month?")
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
