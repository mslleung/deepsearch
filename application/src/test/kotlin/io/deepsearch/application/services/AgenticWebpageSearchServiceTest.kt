package io.deepsearch.application.services

import com.sun.net.httpserver.HttpServer
import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.domain.agents.ActionWithOutcome
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import io.deepsearch.domain.testing.IsolatedKoinTest
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for AgenticWebpageSearchService.
 *
 * Serves controlled HTML pages from a local HTTP server where content is
 * definitively hidden behind JS-controlled interactive elements (display:none).
 * This guarantees the agent MUST click to find the answer.
 *
 * Requires deepsearch-browser running at localhost:8090 and GOOGLE_API_KEY env var.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgenticWebpageSearchServiceTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()
    private lateinit var testServer: HttpServer
    private var testPort: Int = 0

    @BeforeAll
    fun setup() {
        koinApp = koinApplication {
            modules(applicationBenchmarkTestModule)
        }
        koinApp.createEagerInstances()
        testKoin = koinApp.koin

        testServer = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)

        testServer.createContext("/accordion") { exchange ->
            val html = ACCORDION_PAGE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/tabs") { exchange ->
            val html = TAB_PAGE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/visible") { exchange ->
            val html = VISIBLE_ANSWER_PAGE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/deep-accordion") { exchange ->
            val html = DEEP_ACCORDION_PAGE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/number-dense") { exchange ->
            val html = NUMBER_DENSE_PAGE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/crowded-labels") { exchange ->
            val html = CROWDED_LABELS_PAGE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/nav-heavy") { exchange ->
            val html = NAVIGATION_HEAVY_PAGE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/offpage-loop") { exchange ->
            val html = OFFPAGE_LOOP_PAGE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/cookie-onetrust") { exchange ->
            val html = COOKIE_ONETRUST_PAGE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/cookie-cookiebot") { exchange ->
            val html = COOKIE_COOKIEBOT_PAGE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/cookie-custom") { exchange ->
            val html = COOKIE_CUSTOM_PAGE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/no-match") { exchange ->
            val html = NONEXISTENT_INFO_PAGE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/long-page-bottom") { exchange ->
            val html = LONG_PAGE_BOTTOM_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/long-page-accordion-bottom") { exchange ->
            val html = LONG_PAGE_ACCORDION_BOTTOM_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.createContext("/searchable-table") { exchange ->
            val html = SEARCHABLE_TABLE_HTML
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        testServer.start()
        testPort = testServer.address.port
        println("Test HTTP server started on port $testPort")
    }

    @AfterAll
    fun teardown() {
        testServer.stop(0)
        applicationScope.close()
        koinApp.close()
    }

    // ==================== Test: Answer is visible without interaction ====================

    @Test
    fun `answer visible content without any clicks`() = runTest(
        testCoroutineDispatcher,
        timeout = 120.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/visible",
            query = "What is the company motto?",
            sessionId = QuerySessionId("test-visible")
        )

        println("=== VISIBLE CONTENT TEST ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer on the visible page")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("INNOVATION-DRIVES-PROGRESS", ignoreCase = true),
            "Answer should contain the motto: ${result.answer}"
        )

        val clickCount = result.actionsPerformed.count { it.action is NavigationAction.Click }
        println("Click actions: $clickCount")
        assertTrue(clickCount == 0, "Should NOT need any clicks for visible content, but had $clickCount")
    }

    // ==================== Test: Answer hidden behind accordion click ====================

    @Test
    fun `click accordion to reveal hidden answer`() = runTest(
        testCoroutineDispatcher,
        timeout = 180.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/accordion",
            query = "What is the refund policy code?",
            sessionId = QuerySessionId("test-accordion-click")
        )

        println("=== ACCORDION CLICK TEST ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("REFUND-GAMMA-77", ignoreCase = true),
            "Answer should contain REFUND-GAMMA-77: ${result.answer}"
        )
        val clickCount = result.actionsPerformed.count { it.action is NavigationAction.Click }
        println("Click actions: $clickCount (VLM may find answer from text without clicking)")
    }

    // ==================== Test: Answer hidden behind tab click ====================

    @Test
    fun `click tab to reveal hidden answer`() = runTest(
        testCoroutineDispatcher,
        timeout = 180.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/tabs",
            query = "What is the enterprise plan price?",
            sessionId = QuerySessionId("test-tab-click")
        )

        println("=== TAB CLICK TEST ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("499", ignoreCase = true),
            "Answer should contain the enterprise price 499: ${result.answer}"
        )
        val clickCount = result.actionsPerformed.count { it.action is NavigationAction.Click }
        println("Click actions: $clickCount (VLM may find answer from text without clicking)")
    }

    // ==================== Test: Answer behind nested accordion (2 levels deep) ====================

    @Test
    fun `click through nested accordions to find deep answer`() = runTest(
        testCoroutineDispatcher,
        timeout = 240.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/deep-accordion",
            query = "What is the secret API key?",
            sessionId = QuerySessionId("test-deep-accordion")
        )

        println("=== DEEP ACCORDION TEST ===")
        printResult(result)

        assertTrue(result.success, "Should find the deeply nested answer")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("SK-DEEP-NESTED-42X", ignoreCase = true),
            "Answer should contain the secret API key: ${result.answer}"
        )
        val clickCount = result.actionsPerformed.count { it.action is NavigationAction.Click }
        println("Click actions: $clickCount (VLM may find answer from text without clicking)")
    }

    // ==================== Test: Query for non-existent information ====================

    @Test
    fun `agent terminates gracefully when queried information does not exist on page`() = runTest(
        testCoroutineDispatcher,
        timeout = 240.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/no-match",
            query = "What is the CEO's birthday?",
            sessionId = QuerySessionId("test-no-match")
        )

        println("=== NON-EXISTENT INFORMATION TEST ===")
        printResult(result)

        val clickCount = result.actionsPerformed.count { it.action is NavigationAction.Click }
        val scrollAtCount = result.actionsPerformed.count { it.action is NavigationAction.ScrollAt }
        val finished = result.actionsPerformed.lastOrNull { it.action is NavigationAction.ExplorationFinished }
        val finishedWithAnswer = finished?.let { (it.action as NavigationAction.ExplorationFinished).answer != null } ?: false
        val exhaustedIterations = result.actionsPerformed.size >= MAX_ITERATIONS

        println("\n--- Behavior Summary ---")
        println("Termination: ${if (finished != null && !finishedWithAnswer) "ExplorationFinished(null)" else if (finishedWithAnswer) "ExplorationFinished(answer) (HALLUCINATION!)" else "Loop exhaustion ($MAX_ITERATIONS iterations)"}")
        println("Total iterations: ${result.actionsPerformed.size} / $MAX_ITERATIONS")
        println("Clicks: $clickCount, ScrollAt: $scrollAtCount")
        println("Token usage: prompt=${result.totalTokenUsage.promptTokens}, output=${result.totalTokenUsage.outputTokens}, total=${result.totalTokenUsage.totalTokens}")

        assertFalse(result.success, "Should NOT report success when the information does not exist on the page")
        assertNull(result.answer, "Should NOT hallucinate an answer — answer must be null")
        assertTrue(
            (finished != null && !finishedWithAnswer) || exhaustedIterations,
            "Agent should terminate via ExplorationFinished(null) or loop exhaustion, not with an answer"
        )
    }

    // ==================== Helpers ====================

    private fun printResult(result: AgenticPageSearchResult) {
        println("Success: ${result.success}")
        println("Answer: ${result.answer}")
        println("Evidence: ${result.evidence}")
        println("Actions (${result.actionsPerformed.size}):")
        result.actionsPerformed.forEachIndexed { idx, action ->
            println("  ${idx + 1}. $action")
        }
        println("Token usage: prompt=${result.totalTokenUsage.promptTokens}, output=${result.totalTokenUsage.outputTokens}, total=${result.totalTokenUsage.totalTokens}")
    }

    // ==================== Test: Page dense with numbers (hallucination-prone) ====================

    @Test
    fun `number-dense page does not waste iterations on label hallucination`() = runTest(
        testCoroutineDispatcher,
        timeout = 180.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/number-dense",
            query = "What is the warranty period for the ProMax model?",
            sessionId = QuerySessionId("test-number-dense")
        )

        println("=== NUMBER-DENSE HALLUCINATION TEST ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer on the number-dense page")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("36", ignoreCase = true) || result.answer!!.contains("three year", ignoreCase = true),
            "Answer should contain the 36-month warranty: ${result.answer}"
        )

        println("Iterations used: ${result.actionsPerformed.size} (max $MAX_ITERATIONS)")
        assertTrue(
            result.actionsPerformed.size <= 6,
            "Should find the answer in 6 or fewer iterations, used ${result.actionsPerformed.size}"
        )
    }

    // ==================== Test: Many labels + overlapping page numbers (hard hallucination) ====================

    @Test
    fun `crowded labels with overlapping page numbers does not hallucinate`() = runTest(
        testCoroutineDispatcher,
        timeout = 240.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/crowded-labels",
            query = "What is the cancellation fee for the Premium plan?",
            sessionId = QuerySessionId("test-crowded-labels")
        )

        println("=== CROWDED LABELS + OVERLAPPING NUMBERS TEST ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer on the crowded page")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("75", ignoreCase = true),
            "Answer should contain the $75 cancellation fee: ${result.answer}"
        )

        val suspectedHallucinations = result.actionsPerformed.count { entry ->
            when (val action = entry.action) {
                is NavigationAction.Click -> (action.resolvedElementLabel ?: 0) >= 25
                is NavigationAction.Type -> action.x >= 25
                else -> false
            }
        }
        println("Suspected hallucinations (label >= 25): $suspectedHallucinations")
        println("Iterations used: ${result.actionsPerformed.size} (max $MAX_ITERATIONS)")
        assertTrue(
            suspectedHallucinations == 0,
            "Should have zero hallucinated labels (>= 25), had $suspectedHallucinations"
        )
        assertTrue(
            result.actionsPerformed.size <= 6,
            "Should find the answer in 6 or fewer iterations, used ${result.actionsPerformed.size}"
        )
    }

    // ==================== Test: Navigation-heavy page with many off-page links ====================

    @Test
    fun `navigation-heavy page finds answer amid many links`() = runTest(
        testCoroutineDispatcher,
        timeout = 240.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/nav-heavy",
            query = "What is the weather cancellation policy code?",
            sessionId = QuerySessionId("test-nav-heavy")
        )

        println("=== NAVIGATION-HEAVY PAGE TEST ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer on the navigation-heavy page")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("WX-CANCEL-88R", ignoreCase = true),
            "Answer should contain the weather cancellation code WX-CANCEL-88R: ${result.answer}"
        )

        println("Off-page links discovered: ${result.discoveredUrls.size}")
        println("Iterations used: ${result.actionsPerformed.size} (max $MAX_ITERATIONS)")
        assertTrue(
            result.actionsPerformed.size <= 8,
            "Should find the answer in 8 or fewer iterations amid many nav links, used ${result.actionsPerformed.size}"
        )
    }

    // ==================== Test: Agent does not loop on off-page navigation links ====================

    @Test
    fun `agent does not repeatedly click same off-page navigation link`() = runTest(
        testCoroutineDispatcher,
        timeout = 240.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/offpage-loop",
            query = "What is the maximum file size for sync?",
            sessionId = QuerySessionId("test-offpage-loop")
        )

        println("=== OFF-PAGE LOOP PREVENTION TEST ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer on the off-page loop page")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("50", ignoreCase = true),
            "Answer should mention 50 GB max file size: ${result.answer}"
        )

        val duplicateUrls = result.discoveredUrls.groupBy { it }.filter { it.value.size > 1 }
        println("Discovered URLs: ${result.discoveredUrls}")
        println("Duplicate off-page URLs: $duplicateUrls")
        assertTrue(
            duplicateUrls.isEmpty(),
            "No off-page URL should be discovered more than once (duplicates: ${duplicateUrls.keys})"
        )

        println("Iterations used: ${result.actionsPerformed.size} (max $MAX_ITERATIONS)")
        assertTrue(
            result.actionsPerformed.size <= 8,
            "Should find the answer in 8 or fewer iterations, used ${result.actionsPerformed.size}"
        )
    }

    // ==================== Test: OneTrust cookie banner dismissed programmatically ====================

    @Test
    fun `onetrust cookie banner is dismissed without wasting VLM iterations`() = runTest(
        testCoroutineDispatcher,
        timeout = 120.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/cookie-onetrust",
            query = "What is the activation code?",
            sessionId = QuerySessionId("test-cookie-onetrust")
        )

        println("=== ONETRUST COOKIE BANNER TEST ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer despite OneTrust cookie overlay")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("ONETRUST-PASS-99", ignoreCase = true),
            "Answer should contain the activation code ONETRUST-PASS-99: ${result.answer}"
        )

        val clickCount = result.actionsPerformed.count { it.action is NavigationAction.Click }
        println("Click actions: $clickCount")
        assertTrue(
            clickCount == 0,
            "Should NOT need any clicks — OneTrust banner should be dismissed programmatically, but had $clickCount clicks"
        )
        assertTrue(
            result.actionsPerformed.size <= 3,
            "Should find the answer in 3 or fewer iterations (content is visible after banner removal), used ${result.actionsPerformed.size}"
        )
    }

    // ==================== Test: Cookiebot cookie banner dismissed programmatically ====================

    @Test
    fun `cookiebot cookie banner is dismissed without wasting VLM iterations`() = runTest(
        testCoroutineDispatcher,
        timeout = 120.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/cookie-cookiebot",
            query = "What is the service activation key?",
            sessionId = QuerySessionId("test-cookie-cookiebot")
        )

        println("=== COOKIEBOT COOKIE BANNER TEST ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer despite Cookiebot cookie overlay")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("COOKIEBOT-PASS-55", ignoreCase = true),
            "Answer should contain the service activation key COOKIEBOT-PASS-55: ${result.answer}"
        )

        val clickCount = result.actionsPerformed.count { it.action is NavigationAction.Click }
        println("Click actions: $clickCount")
        assertTrue(
            clickCount == 0,
            "Should NOT need any clicks — Cookiebot banner should be dismissed programmatically, but had $clickCount clicks"
        )
        assertTrue(
            result.actionsPerformed.size <= 3,
            "Should find the answer in 3 or fewer iterations (content is visible after banner removal), used ${result.actionsPerformed.size}"
        )
    }

    // ==================== Test: Unknown cookie banner handled by VLM fallback ====================

    @Test
    fun `unknown cookie banner is handled by VLM with at most one extra iteration`() = runTest(
        testCoroutineDispatcher,
        timeout = 180.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/cookie-custom",
            query = "What is today's access code?",
            sessionId = QuerySessionId("test-cookie-custom")
        )

        println("=== CUSTOM/UNKNOWN COOKIE BANNER TEST ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer even with an unknown cookie banner")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("CUSTOM-BANNER-77", ignoreCase = true),
            "Answer should contain the access code CUSTOM-BANNER-77: ${result.answer}"
        )

        val clickCount = result.actionsPerformed.count { it.action is NavigationAction.Click }
        println("Click actions: $clickCount")
        assertTrue(
            result.actionsPerformed.size <= 5,
            "Should find the answer in 5 or fewer iterations (1 to dismiss banner + finding answer), used ${result.actionsPerformed.size}"
        )
    }

    // ==================== Efficiency Tests: scroll vs search_text ====================

    @Test
    fun `long page - answer at bottom found via search_text not excessive scrolling`() = runTest(
        testCoroutineDispatcher,
        timeout = 180.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/long-page-bottom",
            query = "What is the system maintenance code?",
            sessionId = QuerySessionId("test-long-page-bottom")
        )

        println("=== LONG PAGE BOTTOM (EFFICIENCY) ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("MAINT-PHOENIX-2024-X9", ignoreCase = true),
            "Answer should contain MAINT-PHOENIX-2024-X9: ${result.answer}"
        )

        assertTrue(
            result.actionsPerformed.size <= 6,
            "Should find the answer in 6 or fewer iterations, used ${result.actionsPerformed.size}"
        )
    }

    @Test
    fun `long page accordion - answer behind FAQ at bottom found efficiently`() = runTest(
        testCoroutineDispatcher,
        timeout = 240.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/long-page-accordion-bottom",
            query = "What is the emergency shutdown procedure?",
            sessionId = QuerySessionId("test-long-page-accordion-bottom")
        )

        println("=== LONG PAGE ACCORDION BOTTOM (EFFICIENCY) ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("ESHUT-DELTA-7X", ignoreCase = true),
            "Answer should contain ESHUT-DELTA-7X: ${result.answer}"
        )

        assertTrue(
            result.actionsPerformed.size <= 8,
            "Should find the answer in 8 or fewer iterations, used ${result.actionsPerformed.size}"
        )
    }

    @Test
    fun `searchable table - find specific row via search_text not scrolling`() = runTest(
        testCoroutineDispatcher,
        timeout = 180.seconds
    ) {
        val result = agenticSearchService.searchWithinPage(
            url = "http://localhost:$testPort/searchable-table",
            query = "What is the processing time for platinum-tier orders?",
            sessionId = QuerySessionId("test-searchable-table")
        )

        println("=== SEARCHABLE TABLE (EFFICIENCY) ===")
        printResult(result)

        assertTrue(result.success, "Should find the answer")
        assertNotNull(result.answer, "Answer should not be null")
        assertTrue(
            result.answer!!.contains("2 hour", ignoreCase = true) ||
                    result.answer!!.contains("2h", ignoreCase = true) ||
                    result.answer!!.contains("120 min", ignoreCase = true),
            "Answer should mention 2 hours processing time for Platinum: ${result.answer}"
        )

        assertTrue(
            result.actionsPerformed.size <= 5,
            "Should find the answer in 5 or fewer iterations, used ${result.actionsPerformed.size}"
        )
    }

    companion object {
        private const val MAX_ITERATIONS = 12

        /**
         * Hard hallucination test: 20+ interactive elements (labels 0-19+) combined with
         * page numbers that OVERLAP with the label range (e.g., "Step 3", "Section 8",
         * "Item 12", "#15"). The answer is behind a specific FAQ accordion click.
         * This forces the VLM to distinguish colored label badges from inline page numbers
         * in a visually crowded viewport.
         */
        val CROWDED_LABELS_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>Acme Corp — Plans & FAQ</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 0; }
                    .layout { display: flex; max-width: 1200px; margin: 0 auto; }
                    nav { background: #1a1a2e; color: white; padding: 8px 24px; display: flex; gap: 16px; align-items: center; }
                    nav a { color: #ccc; text-decoration: none; font-size: 14px; }
                    .sidebar { width: 220px; padding: 16px; border-right: 1px solid #ddd; font-size: 13px; }
                    .sidebar a { display: block; padding: 6px 0; color: #0066c0; text-decoration: none; }
                    .main { flex: 1; padding: 24px; }
                    table { width: 100%; border-collapse: collapse; margin: 16px 0; font-size: 14px; }
                    th, td { border: 1px solid #ddd; padding: 10px; text-align: center; }
                    th { background: #f5f5f5; }
                    .faq-item { border: 1px solid #ccc; margin: 8px 0; border-radius: 6px; }
                    .faq-q { padding: 12px 16px; cursor: pointer; background: #f8f8f8; border: none; width: 100%; text-align: left; font-size: 14px; }
                    .faq-a { padding: 12px 16px; border-top: 1px solid #eee; }
                    .badge { display: inline-block; background: #e74c3c; color: white; border-radius: 50%; width: 20px; height: 20px; text-align: center; line-height: 20px; font-size: 11px; margin-left: 4px; }
                </style>
            </head>
            <body>
                <nav>
                    <a href="#">Home</a>
                    <a href="#">Products</a>
                    <a href="#">Pricing</a>
                    <a href="#">Documentation</a>
                    <a href="#">Blog</a>
                    <a href="#">Support</a>
                    <a href="#">Contact</a>
                    <a href="#">Login</a>
                </nav>
                <div class="layout">
                    <div class="sidebar">
                        <strong>Quick Links</strong>
                        <a href="#">Overview (Section 1)</a>
                        <a href="#">Features (Section 2)</a>
                        <a href="#">Pricing (Section 3)</a>
                        <a href="#">Compare Plans (Section 4)</a>
                        <a href="#">FAQ (Section 5)</a>
                        <a href="#">Terms (Section 6)</a>
                        <a href="#">Privacy (Section 7)</a>
                        <a href="#">Refunds (Section 8)</a>
                    </div>
                    <div class="main">
                        <h1>Pricing Plans</h1>
                        <p>Over 15 million customers trust Acme Corp. Join plan #3 (our most popular) or explore all 8 options below.</p>

                        <table>
                            <tr>
                                <th>Feature</th>
                                <th>Basic (${'$'}9/mo)<br>Plan #1</th>
                                <th>Standard (${'$'}19/mo)<br>Plan #2</th>
                                <th>Premium (${'$'}49/mo)<br>Plan #3</th>
                                <th>Enterprise (${'$'}99/mo)<br>Plan #4</th>
                            </tr>
                            <tr><td>Users</td><td>1</td><td>5</td><td>15</td><td>Unlimited</td></tr>
                            <tr><td>Storage</td><td>5 GB</td><td>25 GB</td><td>100 GB</td><td>500 GB</td></tr>
                            <tr><td>API calls/day</td><td>100</td><td>1,000</td><td>10,000</td><td>Unlimited</td></tr>
                            <tr><td>Support</td><td>Email (48h)</td><td>Email (24h)</td><td>Priority (4h)</td><td>Dedicated (1h)</td></tr>
                            <tr><td>Uptime SLA</td><td>99%</td><td>99.5%</td><td>99.9%</td><td>99.99%</td></tr>
                        </table>

                        <p>Step 1: Choose your plan. Step 2: Enter billing info. Step 3: Start your 14-day trial. Offer code #12 expires Dec 31.</p>

                        <h2>Frequently Asked Questions <span class="badge">8</span></h2>

                        <div class="faq-item">
                            <button class="faq-q" onclick="toggle(this)">What payment methods do you accept?</button>
                            <div class="faq-a" style="display:none">We accept Visa, MasterCard, AMEX, and PayPal. Invoice available for Plan #4.</div>
                        </div>
                        <div class="faq-item">
                            <button class="faq-q" onclick="toggle(this)">Can I switch plans mid-billing cycle?</button>
                            <div class="faq-a" style="display:none">Yes, upgrades take effect immediately. Downgrades apply at the next billing date. See Item 12 in our Terms of Service.</div>
                        </div>
                        <div class="faq-item">
                            <button class="faq-q" onclick="toggle(this)">What is the cancellation fee for the Premium plan?</button>
                            <div class="faq-a" style="display:none">The cancellation fee for the Premium plan (Plan #3) is <strong>${'$'}75</strong> if cancelled within the first 6 months. After 6 months, cancellation is free. Reference: Policy 15-C.</div>
                        </div>
                        <div class="faq-item">
                            <button class="faq-q" onclick="toggle(this)">Is there a free trial?</button>
                            <div class="faq-a" style="display:none">All plans include a 14-day free trial. No credit card required for Basic (Plan #1).</div>
                        </div>
                        <div class="faq-item">
                            <button class="faq-q" onclick="toggle(this)">Do you offer educational discounts?</button>
                            <div class="faq-a" style="display:none">Yes, 40% off for verified .edu email addresses. Apply via Form 7-B on our website.</div>
                        </div>

                        <p style="color: #888; font-size: 12px; margin-top: 32px;">
                            Acme Corp — Founded 2019 — 8 offices worldwide — Support ticket average: 3.2 hours —
                            Rating: 4.7/5 from 12,450 reviews — Tax ID: 15-8842091 — Page 1 of 3
                        </p>
                    </div>
                </div>
                <script>
                    function toggle(btn) {
                        var a = btn.nextElementSibling;
                        a.style.display = a.style.display === 'none' ? 'block' : 'none';
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        /**
         * Page densely packed with numbers that could confuse the VLM into thinking
         * they are element labels: product IDs (#874, #401), prices ($1,299), stock counts,
         * and rating scores. The actual answer is behind a "Show Details" button.
         */
        val NUMBER_DENSE_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>Product Catalog</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 900px; margin: 40px auto; }
                    table { width: 100%; border-collapse: collapse; margin: 20px 0; }
                    th, td { border: 1px solid #ddd; padding: 12px 16px; text-align: left; }
                    th { background: #f5f5f5; }
                    .product-id { font-weight: bold; color: #333; }
                    .price { font-size: 18px; color: #b12704; font-weight: bold; }
                    .stock { color: #007600; }
                    .rating { color: #e47911; }
                    .btn { padding: 8px 16px; background: #0066c0; color: white; border: none; cursor: pointer; border-radius: 4px; }
                    .details { padding: 16px; background: #f9f9f9; border: 1px solid #ddd; margin: 8px 0; }
                </style>
            </head>
            <body>
                <h1>Electronics Product Catalog</h1>
                <p>Showing 5 of 1,247 products. Page 3 of 125. Order #90847.</p>

                <table>
                    <tr><th>ID</th><th>Product</th><th>Price</th><th>Stock</th><th>Rating</th><th>SKU</th></tr>
                    <tr><td class="product-id">#401</td><td>UltraView Monitor 27"</td><td class="price">${'$'}1,299</td><td class="stock">847 in stock</td><td class="rating">4.7/5 (2,341 reviews)</td><td>SKU-90124</td></tr>
                    <tr><td class="product-id">#402</td><td>SpeedType Keyboard</td><td class="price">${'$'}189</td><td class="stock">3,421 in stock</td><td class="rating">4.5/5 (892 reviews)</td><td>SKU-90125</td></tr>
                    <tr><td class="product-id">#403</td><td>ProMax Laptop 15"</td><td class="price">${'$'}2,499</td><td class="stock">156 in stock</td><td class="rating">4.8/5 (5,671 reviews)</td><td>SKU-90126</td></tr>
                    <tr><td class="product-id">#404</td><td>SoundPro Headphones</td><td class="price">${'$'}349</td><td class="stock">2,108 in stock</td><td class="rating">4.6/5 (1,234 reviews)</td><td>SKU-90127</td></tr>
                    <tr><td class="product-id">#405</td><td>PowerDock Station</td><td class="price">${'$'}279</td><td class="stock">967 in stock</td><td class="rating">4.3/5 (456 reviews)</td><td>SKU-90128</td></tr>
                </table>

                <p>Reference codes: Region 874, Warehouse 1055, Batch 2209, Shipment 663.</p>

                <div>
                    <button class="btn" onclick="document.getElementById('details-403').style.display = document.getElementById('details-403').style.display === 'none' ? 'block' : 'none'">
                        Show ProMax Warranty Details
                    </button>
                    <div id="details-403" class="details" style="display:none">
                        <h3>ProMax Laptop 15" — Warranty Information</h3>
                        <p>Standard warranty period: <strong>36 months</strong> from date of purchase.</p>
                        <p>Extended warranty available: 60 months for ${'$'}199. Coverage ID: WRN-90403.</p>
                    </div>
                </div>

                <p style="margin-top: 40px; color: #666; font-size: 12px;">
                    Customer service: 1-800-555-0199 | Fax: 1-800-555-0200 | Store #1042, Mall Level 3, Unit 874-B
                </p>
            </body>
            </html>
        """.trimIndent()

        /**
         * Page with the answer directly visible -- no interaction needed.
         */
        val VISIBLE_ANSWER_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head><title>About Us</title></head>
            <body>
                <h1>About Our Company</h1>
                <p>We are a technology company founded in 2020.</p>
                <p>Our company motto is: <strong>INNOVATION-DRIVES-PROGRESS</strong></p>
                <p>We serve customers in over 50 countries.</p>
            </body>
            </html>
        """.trimIndent()

        /**
         * Page with FAQ accordion -- answer is hidden behind a click.
         * Uses JS-controlled display:none so the content is invisible
         * to both the screenshot and extractTextContent().
         */
        val ACCORDION_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>FAQ</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 700px; margin: 40px auto; }
                    .faq-item { border: 1px solid #ccc; margin: 10px 0; border-radius: 8px; }
                    .faq-question {
                        padding: 16px; cursor: pointer; background: #f5f5f5;
                        border: none; width: 100%; text-align: left; font-size: 16px;
                        border-radius: 8px;
                    }
                    .faq-question:hover { background: #e8e8e8; }
                    .faq-answer { padding: 16px; border-top: 1px solid #eee; }
                </style>
            </head>
            <body>
                <h1>Frequently Asked Questions</h1>
                <p>Click on a question to see the answer.</p>

                <div class="faq-item">
                    <button class="faq-question" onclick="toggleAnswer('a1')">What are your business hours?</button>
                    <div id="a1" class="faq-answer" style="display:none">We are open Monday through Friday, 9 AM to 5 PM EST.</div>
                </div>

                <div class="faq-item">
                    <button class="faq-question" onclick="toggleAnswer('a2')">What is your refund policy?</button>
                    <div id="a2" class="faq-answer" style="display:none">Our refund policy code is <strong>REFUND-GAMMA-77</strong>. You can request a full refund within 30 days of purchase.</div>
                </div>

                <div class="faq-item">
                    <button class="faq-question" onclick="toggleAnswer('a3')">How do I contact support?</button>
                    <div id="a3" class="faq-answer" style="display:none">Email us at support@example.com or call 1-800-EXAMPLE.</div>
                </div>

                <script>
                    function toggleAnswer(id) {
                        var el = document.getElementById(id);
                        el.style.display = el.style.display === 'none' ? 'block' : 'none';
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        /**
         * Page with tab panels -- the enterprise pricing is in a non-active tab.
         */
        val TAB_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>Pricing</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 700px; margin: 40px auto; }
                    .tab-bar { display: flex; gap: 0; border-bottom: 2px solid #333; }
                    .tab-btn {
                        padding: 12px 24px; cursor: pointer; background: #f0f0f0;
                        border: 1px solid #ccc; border-bottom: none; font-size: 16px;
                    }
                    .tab-btn.active { background: white; font-weight: bold; border-bottom: 2px solid white; margin-bottom: -2px; }
                    .tab-panel { padding: 24px; border: 1px solid #ccc; border-top: none; }
                </style>
            </head>
            <body>
                <h1>Our Pricing Plans</h1>
                <p>Choose the plan that fits your needs.</p>

                <div class="tab-bar">
                    <button class="tab-btn active" onclick="showTab('starter')">Starter</button>
                    <button class="tab-btn" onclick="showTab('professional')">Professional</button>
                    <button class="tab-btn" onclick="showTab('enterprise')">Enterprise</button>
                </div>

                <div id="tab-starter" class="tab-panel">
                    <h2>Starter Plan</h2>
                    <p>Price: <strong>${'$'}29/month</strong></p>
                    <p>Includes 5 users, 10GB storage, email support.</p>
                </div>
                <div id="tab-professional" class="tab-panel" style="display:none">
                    <h2>Professional Plan</h2>
                    <p>Price: <strong>${'$'}99/month</strong></p>
                    <p>Includes 25 users, 100GB storage, priority support.</p>
                </div>
                <div id="tab-enterprise" class="tab-panel" style="display:none">
                    <h2>Enterprise Plan</h2>
                    <p>Price: <strong>${'$'}499/month</strong></p>
                    <p>Includes unlimited users, 1TB storage, dedicated account manager, SLA guarantee.</p>
                </div>

                <script>
                    function showTab(name) {
                        document.querySelectorAll('.tab-panel').forEach(function(p) { p.style.display = 'none'; });
                        document.querySelectorAll('.tab-btn').forEach(function(b) { b.classList.remove('active'); });
                        document.getElementById('tab-' + name).style.display = 'block';
                        event.target.classList.add('active');
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        /**
         * Page with nested accordions -- the secret answer is two clicks deep.
         */
        val DEEP_ACCORDION_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>Developer Documentation</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 700px; margin: 40px auto; }
                    .section { border: 1px solid #ddd; margin: 12px 0; border-radius: 8px; }
                    .section-header {
                        padding: 14px 18px; cursor: pointer; background: #f7f7f7;
                        border: none; width: 100%; text-align: left; font-size: 16px;
                        font-weight: bold;
                    }
                    .section-body { padding: 18px; display: none; border-top: 1px solid #eee; }
                    .subsection { border: 1px solid #e0e0e0; margin: 10px 0; border-radius: 6px; }
                    .subsection-header {
                        padding: 10px 14px; cursor: pointer; background: #fafafa;
                        border: none; width: 100%; text-align: left; font-size: 14px;
                    }
                    .subsection-body { padding: 14px; display: none; border-top: 1px solid #f0f0f0; }
                </style>
            </head>
            <body>
                <h1>Developer Documentation</h1>
                <p>Expand sections below to find what you need.</p>

                <div class="section">
                    <button class="section-header" onclick="toggle(this)">Getting Started</button>
                    <div class="section-body">
                        <p>Follow our quickstart guide to set up your environment.</p>
                    </div>
                </div>

                <div class="section">
                    <button class="section-header" onclick="toggle(this)">Authentication & API Keys</button>
                    <div class="section-body">
                        <p>This section covers API key management.</p>

                        <div class="subsection">
                            <button class="subsection-header" onclick="toggle(this)">How to generate an API key</button>
                            <div class="subsection-body">
                                <p>Go to Settings &gt; API Keys &gt; Generate New Key.</p>
                            </div>
                        </div>

                        <div class="subsection">
                            <button class="subsection-header" onclick="toggle(this)">What is the secret API key?</button>
                            <div class="subsection-body">
                                <p>The secret API key for the sandbox environment is: <strong>SK-DEEP-NESTED-42X</strong></p>
                                <p>Keep this key confidential. Do not share it publicly.</p>
                            </div>
                        </div>

                        <div class="subsection">
                            <button class="subsection-header" onclick="toggle(this)">Rate limits</button>
                            <div class="subsection-body">
                                <p>Free tier: 100 requests/min. Pro tier: 10,000 requests/min.</p>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="section">
                    <button class="section-header" onclick="toggle(this)">Webhooks</button>
                    <div class="section-body">
                        <p>Configure webhooks to receive real-time event notifications.</p>
                    </div>
                </div>

                <script>
                    function toggle(btn) {
                        var body = btn.nextElementSibling;
                        body.style.display = body.style.display === 'none' || body.style.display === '' ? 'block' : 'none';
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        /**
         * Navigation-heavy page modeled after strangersoccer.com/help.
         * 25+ off-page <a> links in header, sidebar, and footer competing with
         * a handful of FAQ accordion buttons. The answer is hidden behind one
         * specific FAQ accordion click.
         */
        val NAVIGATION_HEAVY_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>StrangerSoccer — Help Center</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; color: #333; }
                    header { background: #1b2838; padding: 12px 24px; display: flex; align-items: center; justify-content: space-between; }
                    header .logo { color: #4fc3f7; font-size: 20px; font-weight: bold; text-decoration: none; }
                    header nav { display: flex; gap: 18px; }
                    header nav a { color: #ccc; text-decoration: none; font-size: 14px; }
                    .breadcrumb { background: #f0f0f0; padding: 10px 24px; font-size: 13px; }
                    .breadcrumb a { color: #0066c0; text-decoration: none; }
                    .layout { display: flex; max-width: 1200px; margin: 0 auto; min-height: 600px; }
                    .sidebar { width: 240px; padding: 20px 16px; border-right: 1px solid #e0e0e0; }
                    .sidebar h3 { font-size: 14px; margin-bottom: 12px; color: #555; text-transform: uppercase; }
                    .sidebar a { display: block; padding: 7px 0; color: #0066c0; text-decoration: none; font-size: 14px; }
                    .sidebar .active { font-weight: bold; color: #333; }
                    .main { flex: 1; padding: 24px 32px; }
                    .main h1 { font-size: 24px; margin-bottom: 8px; }
                    .main .subtitle { color: #666; margin-bottom: 24px; font-size: 14px; }
                    .faq-item { border: 1px solid #ddd; margin: 8px 0; border-radius: 6px; overflow: hidden; }
                    .faq-q { padding: 14px 18px; cursor: pointer; background: #fafafa; border: none; width: 100%; text-align: left; font-size: 15px; display: flex; justify-content: space-between; align-items: center; }
                    .faq-q:hover { background: #f0f0f0; }
                    .faq-q::after { content: '+'; font-size: 18px; color: #888; }
                    .faq-a { padding: 14px 18px; border-top: 1px solid #eee; font-size: 14px; line-height: 1.6; }
                    footer { background: #1b2838; color: #aaa; padding: 24px; font-size: 13px; }
                    footer .footer-links { display: flex; gap: 24px; flex-wrap: wrap; margin-bottom: 12px; }
                    footer a { color: #8bb4d9; text-decoration: none; }
                </style>
            </head>
            <body>
                <header>
                    <a class="logo" href="https://strangersoccer.com">StrangerSoccer</a>
                    <nav>
                        <a href="https://strangersoccer.com/games">Find Games</a>
                        <a href="https://strangersoccer.com/leagues">Leagues</a>
                        <a href="https://strangersoccer.com/venues">Venues</a>
                        <a href="https://strangersoccer.com/pricing">Pricing</a>
                        <a href="https://strangersoccer.com/about">About</a>
                        <a href="https://strangersoccer.com/blog">Blog</a>
                        <a href="https://strangersoccer.com/contact">Contact</a>
                        <a href="https://strangersoccer.com/login">Log In</a>
                        <a href="https://strangersoccer.com/signup">Sign Up</a>
                    </nav>
                </header>

                <div class="breadcrumb">
                    <a href="https://strangersoccer.com">Home</a> &gt;
                    <a href="https://strangersoccer.com/help">Help Center</a> &gt;
                    <span>Other</span>
                </div>

                <div class="layout">
                    <div class="sidebar">
                        <h3>Help Categories</h3>
                        <a href="https://strangersoccer.com/help?category=getting-started">Getting Started</a>
                        <a href="https://strangersoccer.com/help?category=account">Account & Profile</a>
                        <a href="https://strangersoccer.com/help?category=payments">Payments & Billing</a>
                        <a href="https://strangersoccer.com/help?category=games">Games & Scheduling</a>
                        <a href="https://strangersoccer.com/help?category=teams">Teams & Leagues</a>
                        <a href="https://strangersoccer.com/help?category=venues">Venues & Locations</a>
                        <a href="https://strangersoccer.com/help?category=safety">Safety & Conduct</a>
                        <a href="https://strangersoccer.com/help?category=mobile">Mobile App</a>
                        <a href="https://strangersoccer.com/help?category=referrals">Referral Program</a>
                        <a class="active" href="#">Other</a>
                    </div>

                    <div class="main">
                        <h1>Other Questions</h1>
                        <p class="subtitle">Can't find what you're looking for? Browse the topics below or contact support.</p>

                        <div class="faq-item">
                            <button class="faq-q" onclick="toggle(this)">How do I delete my account?</button>
                            <div class="faq-a" style="display:none">Go to Settings &gt; Account &gt; Delete Account. You have 30 days to reactivate before data is permanently removed.</div>
                        </div>
                        <div class="faq-item">
                            <button class="faq-q" onclick="toggle(this)">What is the guest policy for games?</button>
                            <div class="faq-a" style="display:none">Each registered player may bring one guest per game at no extra charge. Guests must sign a waiver at the venue. The guest limit per game is capped at 3 across all players.</div>
                        </div>
                        <div class="faq-item">
                            <button class="faq-q" onclick="toggle(this)">What is the weather cancellation policy?</button>
                            <div class="faq-a" style="display:none">The weather cancellation policy code is <strong>WX-CANCEL-88R</strong>. Games are automatically cancelled if the National Weather Service issues a severe weather warning for the venue area within 2 hours of game time. All players receive a full credit to their account within 24 hours.</div>
                        </div>
                        <div class="faq-item">
                            <button class="faq-q" onclick="toggle(this)">Can I change the game time after creation?</button>
                            <div class="faq-a" style="display:none">Game creators can reschedule up to 12 hours before kickoff. All registered players will be notified automatically. Rescheduling within 12 hours requires approval from at least 80% of registered players.</div>
                        </div>
                        <div class="faq-item">
                            <button class="faq-q" onclick="toggle(this)">How do I report a bug or suggest a feature?</button>
                            <div class="faq-a" style="display:none">Email feedback@strangersoccer.com with the subject line "Bug Report" or "Feature Request". Include screenshots if applicable. We review all submissions within 48 hours.</div>
                        </div>
                    </div>
                </div>

                <footer>
                    <div class="footer-links">
                        <a href="https://strangersoccer.com/terms">Terms of Service</a>
                        <a href="https://strangersoccer.com/privacy">Privacy Policy</a>
                        <a href="https://strangersoccer.com/cookies">Cookie Policy</a>
                        <a href="https://strangersoccer.com/accessibility">Accessibility</a>
                        <a href="https://strangersoccer.com/careers">Careers</a>
                        <a href="https://strangersoccer.com/press">Press</a>
                        <a href="https://strangersoccer.com/investors">Investors</a>
                    </div>
                    <p>&copy; 2024 StrangerSoccer Inc. All rights reserved.</p>
                </footer>

                <script>
                    function toggle(btn) {
                        var a = btn.nextElementSibling;
                        a.style.display = a.style.display === 'none' ? 'block' : 'none';
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        /**
         * Page designed to tempt the agent into repeatedly clicking a prominent
         * off-page CTA link. The large hero CTA navigates away, while the actual
         * answer is in a small FAQ accordion further down the page.
         */
        val OFFPAGE_LOOP_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>CloudSync — Product Page</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; color: #333; }
                    nav { background: #0d1b2a; padding: 12px 24px; display: flex; gap: 20px; align-items: center; }
                    nav a { color: #8bb4d9; text-decoration: none; font-size: 14px; }
                    nav .brand { color: #4fc3f7; font-weight: bold; font-size: 18px; }
                    .hero { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; text-align: center; padding: 60px 24px; }
                    .hero h1 { font-size: 36px; margin-bottom: 16px; }
                    .hero p { font-size: 18px; margin-bottom: 24px; max-width: 600px; margin-left: auto; margin-right: auto; }
                    .hero .cta { display: inline-block; background: #ff6b35; color: white; padding: 16px 48px; border-radius: 8px; text-decoration: none; font-size: 18px; font-weight: bold; }
                    .hero .cta-secondary { display: inline-block; margin-left: 16px; color: white; padding: 16px 32px; border: 2px solid white; border-radius: 8px; text-decoration: none; font-size: 16px; }
                    .features { display: flex; gap: 24px; max-width: 900px; margin: 40px auto; padding: 0 24px; }
                    .feature-card { flex: 1; padding: 24px; border: 1px solid #e0e0e0; border-radius: 8px; text-align: center; }
                    .feature-card h3 { margin-bottom: 8px; }
                    .feature-card a { color: #667eea; text-decoration: none; font-size: 14px; }
                    .faq-section { max-width: 700px; margin: 40px auto; padding: 0 24px; }
                    .faq-section h2 { margin-bottom: 16px; font-size: 20px; }
                    .faq-item { border: 1px solid #ddd; margin: 8px 0; border-radius: 6px; }
                    .faq-q { padding: 14px 18px; cursor: pointer; background: #fafafa; border: none; width: 100%; text-align: left; font-size: 14px; }
                    .faq-a { padding: 14px 18px; border-top: 1px solid #eee; font-size: 14px; }
                </style>
            </head>
            <body>
                <nav>
                    <a class="brand" href="https://cloudsync.example.com">CloudSync</a>
                    <a href="https://cloudsync.example.com/features">Features</a>
                    <a href="https://cloudsync.example.com/pricing">Pricing</a>
                    <a href="https://cloudsync.example.com/docs">Documentation</a>
                    <a href="https://cloudsync.example.com/blog">Blog</a>
                    <a href="https://cloudsync.example.com/login">Login</a>
                </nav>

                <div class="hero">
                    <h1>Sync Everything. Everywhere.</h1>
                    <p>CloudSync keeps your files, databases, and configurations in perfect harmony across all your environments.</p>
                    <a class="cta" href="https://cloudsync.example.com/signup">Start Free Trial</a>
                    <a class="cta-secondary" href="https://cloudsync.example.com/demo">Watch Demo</a>
                </div>

                <div class="features">
                    <div class="feature-card">
                        <h3>Real-time Sync</h3>
                        <p>Sub-second propagation across regions.</p>
                        <a href="https://cloudsync.example.com/features/realtime">Learn more &rarr;</a>
                    </div>
                    <div class="feature-card">
                        <h3>Conflict Resolution</h3>
                        <p>Automatic merge with manual override.</p>
                        <a href="https://cloudsync.example.com/features/conflicts">Learn more &rarr;</a>
                    </div>
                    <div class="feature-card">
                        <h3>Enterprise Security</h3>
                        <p>SOC 2, HIPAA, and GDPR compliant.</p>
                        <a href="https://cloudsync.example.com/features/security">Learn more &rarr;</a>
                    </div>
                </div>

                <div class="faq-section">
                    <h2>Frequently Asked Questions</h2>
                    <div class="faq-item">
                        <button class="faq-q" onclick="toggle(this)">What platforms are supported?</button>
                        <div class="faq-a" style="display:none">CloudSync supports macOS, Windows, Linux, iOS, and Android. Browser extension available for Chrome and Firefox.</div>
                    </div>
                    <div class="faq-item">
                        <button class="faq-q" onclick="toggle(this)">What is the maximum file size for sync?</button>
                        <div class="faq-a" style="display:none">The maximum file size for sync is <strong>50 GB</strong> per file on all plans. The free tier has a total storage limit of 15 GB. Sync configuration code: <strong>SYNC-MAX-50G</strong>.</div>
                    </div>
                    <div class="faq-item">
                        <button class="faq-q" onclick="toggle(this)">How do I contact support?</button>
                        <div class="faq-a" style="display:none">Email support@cloudsync.example.com or use the in-app chat. Enterprise customers get dedicated Slack channels.</div>
                    </div>
                </div>

                <script>
                    function toggle(btn) {
                        var a = btn.nextElementSibling;
                        a.style.display = a.style.display === 'none' ? 'block' : 'none';
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        /**
         * Page with visible content behind a full-screen OneTrust-style cookie consent overlay.
         * The overlay uses the exact CSS selectors from COOKIE_ACCEPT_SELECTORS and
         * COOKIE_OVERLAY_SELECTORS so dismissCookieBanner should handle it programmatically.
         */
        val COOKIE_ONETRUST_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>TechCorp — Company Info</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 700px; margin: 40px auto; padding: 24px; }
                </style>
            </head>
            <body>
                <h1>Welcome to TechCorp</h1>
                <p>We are a technology company providing innovative solutions.</p>
                <p>Our activation code is: <strong>ONETRUST-PASS-99</strong></p>
                <p>Contact us at info@techcorp.example.com for more information.</p>

                <div id="onetrust-consent-sdk" style="position:fixed;top:0;left:0;width:100%;height:100%;z-index:10000;">
                    <div style="position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);"></div>
                    <div style="position:fixed;bottom:0;left:0;width:100%;background:white;padding:24px;box-shadow:0 -2px 10px rgba(0,0,0,0.2);z-index:10001;">
                        <h3>We value your privacy</h3>
                        <p style="font-size:14px;color:#555;margin:12px 0;">We use cookies to enhance your browsing experience, serve personalized ads or content, and analyze our traffic.</p>
                        <div style="display:flex;gap:12px;margin-top:16px;">
                            <button id="onetrust-accept-btn-handler" onclick="document.getElementById('onetrust-consent-sdk').style.display='none'" style="background:#1f96f3;color:white;border:none;padding:12px 32px;border-radius:4px;cursor:pointer;font-size:14px;">Accept All</button>
                            <button style="background:white;border:1px solid #ccc;padding:12px 32px;border-radius:4px;cursor:pointer;font-size:14px;">Reject All</button>
                            <button style="background:white;border:1px solid #ccc;padding:12px 32px;border-radius:4px;cursor:pointer;font-size:14px;">Customize Settings</button>
                        </div>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        /**
         * Page with visible content behind a Cookiebot-style cookie consent dialog.
         * Uses #CybotCookiebotDialog and #CybotCookiebotDialogBodyUnderlay selectors
         * to verify that the second CMP in the selector list is also handled.
         */
        val COOKIE_COOKIEBOT_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>DataHub — Service Info</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 700px; margin: 40px auto; padding: 24px; }
                </style>
            </head>
            <body>
                <h1>Welcome to DataHub Services</h1>
                <p>Your trusted data processing partner since 2018.</p>
                <p>The service activation key is: <strong>COOKIEBOT-PASS-55</strong></p>
                <p>For enterprise inquiries, contact sales@datahub.example.com.</p>

                <div id="CybotCookiebotDialogBodyUnderlay" style="position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:9999;"></div>
                <div id="CybotCookiebotDialog" style="position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);background:white;padding:32px;border-radius:8px;box-shadow:0 4px 20px rgba(0,0,0,0.3);z-index:10000;max-width:500px;width:90%;">
                    <h3 style="margin-bottom:12px;">This website uses cookies</h3>
                    <p style="font-size:14px;color:#555;margin-bottom:20px;">We use cookies to personalise content and ads, to provide social media features and to analyse our traffic.</p>
                    <div style="display:flex;gap:12px;flex-wrap:wrap;">
                        <button id="CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll" onclick="document.getElementById('CybotCookiebotDialog').style.display='none';document.getElementById('CybotCookiebotDialogBodyUnderlay').style.display='none';" style="background:#00a050;color:white;border:none;padding:12px 24px;border-radius:4px;cursor:pointer;font-size:14px;flex:1;">Allow All</button>
                        <button id="CybotCookiebotDialogBodyButtonDecline" style="background:white;border:1px solid #ccc;padding:12px 24px;border-radius:4px;cursor:pointer;font-size:14px;flex:1;">Deny</button>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        /**
         * Page with content behind a custom cookie banner using non-standard
         * CSS selectors that dismissCookieBanner will NOT recognize. The banner
         * is fully opaque and covers the entire viewport so the VLM agent
         * must click through it to find the answer — testing fallback behavior.
         */
        val COOKIE_CUSTOM_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>Global News Portal</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 700px; margin: 40px auto; padding: 24px; }
                    .custom-cookie-popup {
                        position: fixed; top: 0; left: 0; width: 100%; height: 100%; z-index: 10000;
                    }
                    .custom-cookie-backdrop {
                        position: fixed; top: 0; left: 0; width: 100%; height: 100%;
                        background: #1a202c;
                    }
                    .custom-cookie-dialog {
                        position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%);
                        background: #2d3748; color: white; padding: 32px; border-radius: 12px;
                        z-index: 10001; max-width: 500px; width: 90%; text-align: center;
                    }
                    .custom-cookie-dialog h2 { margin-bottom: 12px; font-size: 20px; }
                    .custom-cookie-dialog p { font-size: 14px; color: #cbd5e0; margin-bottom: 20px; }
                    .cookie-accept-custom {
                        background: #48bb78; color: white; border: none; padding: 12px 32px;
                        border-radius: 6px; cursor: pointer; font-size: 16px; font-weight: bold;
                    }
                    .cookie-decline-custom {
                        background: transparent; border: 1px solid #718096; color: #a0aec0;
                        padding: 12px 24px; border-radius: 6px; cursor: pointer; font-size: 14px;
                        margin-left: 8px;
                    }
                </style>
            </head>
            <body>
                <h1>Global News Portal</h1>
                <p>Breaking news from around the world.</p>
                <p>Today's access code is: <strong>CUSTOM-BANNER-77</strong></p>
                <p>Updated hourly. Subscribe for premium content.</p>

                <div class="custom-cookie-popup">
                    <div class="custom-cookie-backdrop"></div>
                    <div class="custom-cookie-dialog">
                        <h2>Cookie Notice</h2>
                        <p>We use cookies to personalise your experience on our site. Please accept cookies to continue browsing.</p>
                        <button class="cookie-accept-custom" onclick="document.querySelector('.custom-cookie-popup').style.display='none'">Accept Cookies</button>
                        <button class="cookie-decline-custom">Manage Preferences</button>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        /**
         * Page with realistic content (restaurant info) but absolutely NO mention
         * of the query topic (CEO birthday). Includes a few interactive FAQ accordions
         * so the agent has elements to explore before concluding the info isn't here.
         */
        /**
         * Long page (~3500px) with the answer buried at the very bottom, after
         * many sections of filler content. Tests whether the VLM uses search_text
         * to jump directly to the answer vs. scrolling 5+ times.
         * Optimal: search_text("maintenance code") -> answer_found (2 iterations).
         */
        val LONG_PAGE_BOTTOM_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>InfraOps — System Administration Guide</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 800px; margin: 0 auto; padding: 24px; color: #333; line-height: 1.7; }
                    h1 { border-bottom: 2px solid #2c3e50; padding-bottom: 12px; }
                    h2 { color: #2c3e50; margin-top: 40px; }
                    .section { margin: 24px 0; padding: 20px; background: #f8f9fa; border-left: 4px solid #3498db; }
                    table { width: 100%; border-collapse: collapse; margin: 16px 0; }
                    th, td { border: 1px solid #ddd; padding: 10px; text-align: left; }
                    th { background: #ecf0f1; }
                    .answer-section { margin-top: 40px; padding: 20px; background: #e8f5e9; border: 1px solid #4caf50; border-radius: 8px; }
                </style>
            </head>
            <body>
                <h1>InfraOps System Administration Guide</h1>
                <p>Version 4.2.1 — Last updated March 2024</p>

                <h2>1. Network Configuration</h2>
                <div class="section">
                    <p>The default gateway is configured at 10.0.0.1 with a subnet mask of 255.255.255.0. DNS servers should point to 10.0.1.53 (primary) and 10.0.1.54 (secondary). VLAN tagging is enabled on ports 1-24 of the core switch.</p>
                    <table>
                        <tr><th>Parameter</th><th>Value</th></tr>
                        <tr><td>Gateway</td><td>10.0.0.1</td></tr>
                        <tr><td>Subnet</td><td>255.255.255.0</td></tr>
                        <tr><td>DNS Primary</td><td>10.0.1.53</td></tr>
                        <tr><td>DNS Secondary</td><td>10.0.1.54</td></tr>
                        <tr><td>MTU</td><td>9000 (jumbo frames)</td></tr>
                    </table>
                </div>

                <h2>2. Storage Management</h2>
                <div class="section">
                    <p>The primary NAS cluster operates on ZFS with RAIDZ2 redundancy across 12 disks. Total raw capacity is 144 TB with 96 TB usable. Snapshots are taken every 15 minutes and retained for 30 days. The backup target is an off-site S3-compatible store at us-west-2.</p>
                    <table>
                        <tr><th>Pool</th><th>Type</th><th>Raw</th><th>Usable</th><th>Used</th></tr>
                        <tr><td>pool-main</td><td>RAIDZ2</td><td>144 TB</td><td>96 TB</td><td>61 TB (64%)</td></tr>
                        <tr><td>pool-archive</td><td>Mirror</td><td>48 TB</td><td>24 TB</td><td>18 TB (75%)</td></tr>
                        <tr><td>pool-scratch</td><td>Stripe</td><td>8 TB</td><td>8 TB</td><td>2 TB (25%)</td></tr>
                    </table>
                </div>

                <h2>3. User Access Policies</h2>
                <div class="section">
                    <p>All user accounts are managed through LDAP with Kerberos authentication. Password policy requires minimum 16 characters, at least one uppercase, one lowercase, one number, and one special character. Passwords expire every 90 days. Failed login attempts are locked after 5 tries for 30 minutes.</p>
                    <p>Role assignments follow the principle of least privilege. Admin access requires approval from two senior engineers and is logged to an immutable audit trail. SSH keys must be ed25519 and rotated annually.</p>
                </div>

                <h2>4. Monitoring & Alerting</h2>
                <div class="section">
                    <p>Prometheus scrapes metrics every 15 seconds from all 247 endpoints. Grafana dashboards are organized by team: Platform (12 dashboards), Data (8 dashboards), Security (6 dashboards). AlertManager routes to PagerDuty for P1/P2 and Slack for P3/P4.</p>
                    <table>
                        <tr><th>Severity</th><th>Response Time</th><th>Channel</th></tr>
                        <tr><td>P1 — Critical</td><td>5 minutes</td><td>PagerDuty + Phone</td></tr>
                        <tr><td>P2 — High</td><td>30 minutes</td><td>PagerDuty</td></tr>
                        <tr><td>P3 — Medium</td><td>4 hours</td><td>Slack #alerts</td></tr>
                        <tr><td>P4 — Low</td><td>Next business day</td><td>Slack #alerts-low</td></tr>
                    </table>
                </div>

                <h2>5. Deployment Procedures</h2>
                <div class="section">
                    <p>All deployments follow the blue-green strategy. The CI/CD pipeline runs on Jenkins with parallel test stages. Code coverage must exceed 80% for merge approval. Canary releases are used for user-facing services with a 5% traffic ramp over 2 hours.</p>
                    <p>Rollback procedures: Automatic rollback triggers if error rate exceeds 1% or p99 latency exceeds 500ms during canary. Manual rollback requires running <code>deploy rollback --env production --revision PREV</code>.</p>
                </div>

                <h2>6. Disaster Recovery</h2>
                <div class="section">
                    <p>RPO (Recovery Point Objective): 15 minutes for Tier 1 services, 1 hour for Tier 2. RTO (Recovery Time Objective): 30 minutes for Tier 1, 4 hours for Tier 2. DR drills are conducted quarterly. The failover site is in a separate availability zone with hot standby for Tier 1 databases.</p>
                </div>

                <h2>7. System Maintenance</h2>
                <div class="answer-section">
                    <p>Scheduled maintenance windows are every Sunday 02:00–06:00 UTC. Emergency maintenance requires VP-level approval.</p>
                    <p>The system maintenance code is: <strong>MAINT-PHOENIX-2024-X9</strong></p>
                    <p>Use this code when filing maintenance tickets in ServiceNow. Include the affected service name, expected downtime, and rollback plan.</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        /**
         * Long page with a FAQ accordion section placed well below the fold (~3000px down).
         * The answer is hidden behind one of the accordion buttons.
         * Optimal: search_text("emergency shutdown") -> click accordion -> answer_found (3 iterations).
         */
        val LONG_PAGE_ACCORDION_BOTTOM_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>AcmePower — Safety Operations Manual</title>
                <style>
                    body { font-family: Georgia, serif; max-width: 800px; margin: 0 auto; padding: 24px; color: #333; line-height: 1.7; }
                    h1 { color: #c0392b; border-bottom: 3px solid #c0392b; padding-bottom: 12px; }
                    h2 { color: #2c3e50; margin-top: 36px; }
                    .content-block { margin: 20px 0; padding: 16px; background: #fdf2e9; border-radius: 8px; }
                    .warning-box { background: #fdedec; border: 2px solid #e74c3c; padding: 16px; border-radius: 8px; margin: 16px 0; }
                    .faq-section { margin-top: 40px; }
                    .faq-item { border: 1px solid #bdc3c7; margin: 10px 0; border-radius: 6px; overflow: hidden; }
                    .faq-q { padding: 14px 18px; cursor: pointer; background: #ecf0f1; border: none; width: 100%; text-align: left; font-size: 15px; font-weight: 600; }
                    .faq-q:hover { background: #d5dbdb; }
                    .faq-a { padding: 14px 18px; border-top: 1px solid #ddd; font-size: 14px; line-height: 1.6; }
                    table { width: 100%; border-collapse: collapse; margin: 16px 0; }
                    th, td { border: 1px solid #ddd; padding: 10px; text-align: left; }
                    th { background: #2c3e50; color: white; }
                </style>
            </head>
            <body>
                <h1>AcmePower Safety Operations Manual</h1>
                <p>Document ID: SOM-2024-R3 | Classification: Internal | Effective: January 1, 2024</p>

                <h2>1. General Safety Principles</h2>
                <div class="content-block">
                    <p>All personnel must complete the Annual Safety Certification (ASC) before operating any equipment rated above 480V. The certification consists of a 40-hour classroom module, 16 hours of hands-on training, and a written exam (minimum passing score: 85%).</p>
                    <p>Personal Protective Equipment (PPE) requirements vary by zone classification. Zone A (high voltage): Arc flash suit, insulated gloves, face shield, and steel-toe boots. Zone B (medium voltage): Safety glasses, insulated gloves, and steel-toe boots. Zone C (low voltage): Safety glasses and closed-toe shoes.</p>
                </div>

                <h2>2. Equipment Inspection Schedule</h2>
                <div class="content-block">
                    <table>
                        <tr><th>Equipment</th><th>Inspection Frequency</th><th>Responsible Team</th><th>Form ID</th></tr>
                        <tr><td>Main Transformer (T1-T4)</td><td>Monthly</td><td>HV Engineering</td><td>INS-001</td></tr>
                        <tr><td>Circuit Breakers (CB series)</td><td>Quarterly</td><td>Protection Team</td><td>INS-002</td></tr>
                        <tr><td>Backup Generators (G1-G8)</td><td>Weekly</td><td>Facilities</td><td>INS-003</td></tr>
                        <tr><td>UPS Systems</td><td>Monthly</td><td>IT Infrastructure</td><td>INS-004</td></tr>
                        <tr><td>Fire Suppression</td><td>Semi-annual</td><td>Safety Officer</td><td>INS-005</td></tr>
                        <tr><td>Cooling Systems (HVAC)</td><td>Monthly</td><td>Facilities</td><td>INS-006</td></tr>
                        <tr><td>Emergency Lighting</td><td>Monthly</td><td>Facilities</td><td>INS-007</td></tr>
                    </table>
                </div>

                <h2>3. Incident Classification</h2>
                <div class="content-block">
                    <p>Incidents are classified into four severity levels based on impact scope and potential harm.</p>
                    <table>
                        <tr><th>Level</th><th>Description</th><th>Response Time</th><th>Notification</th></tr>
                        <tr><td>Level 1 — Critical</td><td>Immediate danger to life or major equipment failure</td><td>Immediate</td><td>CEO, COO, Safety Director</td></tr>
                        <tr><td>Level 2 — Serious</td><td>Significant equipment damage or safety system failure</td><td>15 minutes</td><td>Plant Manager, Safety Officer</td></tr>
                        <tr><td>Level 3 — Moderate</td><td>Minor equipment malfunction, no safety impact</td><td>1 hour</td><td>Shift Supervisor</td></tr>
                        <tr><td>Level 4 — Minor</td><td>Documentation error, minor non-compliance</td><td>Next shift</td><td>Team Lead</td></tr>
                    </table>
                </div>

                <h2>4. Environmental Compliance</h2>
                <div class="content-block">
                    <p>All operations must comply with EPA regulations 40 CFR Parts 260-273. Emissions monitoring stations are positioned at coordinates NE-4, SW-7, and C-12. Monthly emissions reports are filed electronically through the CEDRI system. The annual compliance audit is scheduled for Q3 each year.</p>
                    <p>Waste handling: PCB-containing equipment must be stored in designated containment areas (Building 7, Section C). Disposal follows DOT shipping requirements and must use licensed haulers. Mercury-containing items are cataloged in the HMIS database.</p>
                </div>

                <h2>5. Training Requirements</h2>
                <div class="content-block">
                    <p>New hire orientation includes 3 days of safety training covering: lockout/tagout (LOTO), confined space entry, hot work permits, fall protection, and hazardous material handling. Refresher training is required annually for all certifications. Specialized training for high-voltage switching operations requires an additional 80 hours and is conducted by certified instructors only.</p>
                </div>

                <div class="warning-box">
                    <strong>IMPORTANT:</strong> All personnel must review and acknowledge the updated safety protocols before their next shift. Failure to comply will result in temporary suspension of operational privileges.
                </div>

                <h2>6. Frequently Asked Questions — Emergency Procedures</h2>
                <div class="faq-section">
                    <div class="faq-item">
                        <button class="faq-q" onclick="toggle(this)">What is the evacuation route for Building 3?</button>
                        <div class="faq-a" style="display:none">Exit through the east corridor to Assembly Point C (parking lot C). Alternate route: south stairwell to Assembly Point D. Do not use elevators. Mobility-impaired personnel should proceed to designated rescue assistance areas on each floor.</div>
                    </div>
                    <div class="faq-item">
                        <button class="faq-q" onclick="toggle(this)">What is the emergency shutdown procedure?</button>
                        <div class="faq-a" style="display:none">The emergency shutdown procedure code is <strong>ESHUT-DELTA-7X</strong>. Steps: (1) Press the Emergency Power Off (EPO) button — red mushroom button located at each exit. (2) Call the Control Room at ext. 5555. (3) Announce "Emergency Shutdown initiated" on the PA system. (4) Evacuate all non-essential personnel. (5) Wait for the All-Clear from the Safety Director before re-entering.</div>
                    </div>
                    <div class="faq-item">
                        <button class="faq-q" onclick="toggle(this)">How do I report a near-miss incident?</button>
                        <div class="faq-a" style="display:none">File a Near-Miss Report (Form NMR-100) within 24 hours of the event. Include: date/time, location, personnel involved, description of the event, and potential consequences. Submit to your Shift Supervisor and the Safety Office. All near-miss reports are reviewed in the weekly safety committee meeting.</div>
                    </div>
                    <div class="faq-item">
                        <button class="faq-q" onclick="toggle(this)">Where are the first aid kits located?</button>
                        <div class="faq-a" style="display:none">First aid kits are located at: Building 1 — Lobby, Floor 2 break room, Floor 3 break room. Building 2 — Each lab entrance. Building 3 — Control room, Generator hall entrance. Outdoor — Assembly Points A, C, and D. AED devices are located next to each first aid kit.</div>
                    </div>
                </div>

                <p style="margin-top: 40px; color: #888; font-size: 12px;">AcmePower Inc. — Document Control: Safety Operations — Rev 3.0 — Approved by: J. Martinez, VP Safety</p>

                <script>
                    function toggle(btn) {
                        var a = btn.nextElementSibling;
                        a.style.display = a.style.display === 'none' ? 'block' : 'none';
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        /**
         * Large table with 25 rows where the answer is in one specific row (platinum tier).
         * Tests whether the VLM uses search_text to jump to the target row vs.
         * scrolling through the entire table.
         * Optimal: search_text("platinum") -> answer_found (2 iterations).
         */
        val SEARCHABLE_TABLE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>OrderFlow — Service Tier SLAs</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 1000px; margin: 0 auto; padding: 24px; color: #333; }
                    h1 { color: #1a237e; }
                    .intro { background: #e8eaf6; padding: 16px; border-radius: 8px; margin: 16px 0; }
                    table { width: 100%; border-collapse: collapse; margin: 24px 0; }
                    th { background: #1a237e; color: white; padding: 12px; text-align: left; position: sticky; top: 0; }
                    td { border: 1px solid #ddd; padding: 10px 12px; }
                    tr:nth-child(even) { background: #f5f5f5; }
                    tr:hover { background: #e3f2fd; }
                    .highlight { background: #fff9c4 !important; font-weight: bold; }
                    .footer { margin-top: 32px; color: #888; font-size: 12px; }
                </style>
            </head>
            <body>
                <h1>OrderFlow — Service Tier SLA Reference</h1>
                <div class="intro">
                    <p>This document lists all service tiers and their processing time guarantees. Processing times are measured from order confirmation to dispatch notification. All times are in business hours unless otherwise noted.</p>
                </div>

                <table>
                    <thead>
                        <tr><th>#</th><th>Tier Name</th><th>Monthly Volume</th><th>Processing Time</th><th>Priority Queue</th><th>Dedicated Rep</th><th>SLA Penalty</th></tr>
                    </thead>
                    <tbody>
                        <tr><td>1</td><td>Micro</td><td>1–10 orders</td><td>72 hours</td><td>No</td><td>No</td><td>None</td></tr>
                        <tr><td>2</td><td>Starter</td><td>11–50 orders</td><td>48 hours</td><td>No</td><td>No</td><td>None</td></tr>
                        <tr><td>3</td><td>Basic</td><td>51–100 orders</td><td>36 hours</td><td>No</td><td>No</td><td>5% credit</td></tr>
                        <tr><td>4</td><td>Basic Plus</td><td>101–200 orders</td><td>30 hours</td><td>No</td><td>No</td><td>5% credit</td></tr>
                        <tr><td>5</td><td>Standard</td><td>201–350 orders</td><td>24 hours</td><td>No</td><td>No</td><td>10% credit</td></tr>
                        <tr><td>6</td><td>Standard Plus</td><td>351–500 orders</td><td>20 hours</td><td>No</td><td>No</td><td>10% credit</td></tr>
                        <tr><td>7</td><td>Professional</td><td>501–750 orders</td><td>16 hours</td><td>Yes</td><td>No</td><td>10% credit</td></tr>
                        <tr><td>8</td><td>Professional Plus</td><td>751–1,000 orders</td><td>14 hours</td><td>Yes</td><td>No</td><td>10% credit</td></tr>
                        <tr><td>9</td><td>Business</td><td>1,001–1,500 orders</td><td>12 hours</td><td>Yes</td><td>No</td><td>15% credit</td></tr>
                        <tr><td>10</td><td>Business Plus</td><td>1,501–2,000 orders</td><td>10 hours</td><td>Yes</td><td>No</td><td>15% credit</td></tr>
                        <tr><td>11</td><td>Growth</td><td>2,001–3,000 orders</td><td>8 hours</td><td>Yes</td><td>No</td><td>15% credit</td></tr>
                        <tr><td>12</td><td>Growth Plus</td><td>3,001–4,000 orders</td><td>7 hours</td><td>Yes</td><td>Shared</td><td>15% credit</td></tr>
                        <tr><td>13</td><td>Scale</td><td>4,001–5,000 orders</td><td>6 hours</td><td>Yes</td><td>Shared</td><td>20% credit</td></tr>
                        <tr><td>14</td><td>Scale Plus</td><td>5,001–7,500 orders</td><td>5 hours</td><td>Yes</td><td>Shared</td><td>20% credit</td></tr>
                        <tr><td>15</td><td>Silver</td><td>7,501–10,000 orders</td><td>4 hours</td><td>Yes</td><td>Shared</td><td>20% credit</td></tr>
                        <tr><td>16</td><td>Silver Plus</td><td>10,001–15,000 orders</td><td>3.5 hours</td><td>Yes</td><td>Shared</td><td>20% credit</td></tr>
                        <tr><td>17</td><td>Gold</td><td>15,001–20,000 orders</td><td>3 hours</td><td>Yes</td><td>Yes</td><td>25% credit</td></tr>
                        <tr><td>18</td><td>Gold Plus</td><td>20,001–30,000 orders</td><td>2.5 hours</td><td>Yes</td><td>Yes</td><td>25% credit</td></tr>
                        <tr><td>19</td><td>Platinum</td><td>30,001–50,000 orders</td><td>2 hours</td><td>Yes</td><td>Yes</td><td>30% credit</td></tr>
                        <tr><td>20</td><td>Platinum Plus</td><td>50,001–75,000 orders</td><td>1.5 hours</td><td>Yes</td><td>Yes</td><td>30% credit</td></tr>
                        <tr><td>21</td><td>Diamond</td><td>75,001–100,000 orders</td><td>1 hour</td><td>Yes</td><td>Yes</td><td>35% credit</td></tr>
                        <tr><td>22</td><td>Diamond Plus</td><td>100,001–150,000 orders</td><td>45 minutes</td><td>Yes</td><td>Yes</td><td>35% credit</td></tr>
                        <tr><td>23</td><td>Elite</td><td>150,001–250,000 orders</td><td>30 minutes</td><td>Yes</td><td>Yes</td><td>40% credit</td></tr>
                        <tr><td>24</td><td>Elite Plus</td><td>250,001–500,000 orders</td><td>20 minutes</td><td>Yes</td><td>Yes</td><td>40% credit</td></tr>
                        <tr><td>25</td><td>Ultimate</td><td>500,001+ orders</td><td>15 minutes</td><td>Yes</td><td>Yes</td><td>50% credit</td></tr>
                    </tbody>
                </table>

                <p class="footer">OrderFlow Inc. — SLA Document v3.7 — Effective January 2024 — Contact: sla@orderflow.example.com</p>
            </body>
            </html>
        """.trimIndent()

        val NONEXISTENT_INFO_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>Bella Cucina — Italian Restaurant</title>
                <style>
                    body { font-family: Georgia, serif; max-width: 800px; margin: 40px auto; padding: 0 24px; color: #333; }
                    h1 { font-size: 28px; }
                    .info-block { background: #faf7f2; border: 1px solid #e0d6c8; padding: 20px; border-radius: 8px; margin: 20px 0; }
                    .info-block p { margin: 6px 0; font-size: 15px; }
                    .faq-item { border: 1px solid #ddd; margin: 8px 0; border-radius: 6px; }
                    .faq-q { padding: 14px 18px; cursor: pointer; background: #fafafa; border: none; width: 100%; text-align: left; font-size: 15px; }
                    .faq-q:hover { background: #f0f0f0; }
                    .faq-a { padding: 14px 18px; border-top: 1px solid #eee; font-size: 14px; line-height: 1.6; }
                </style>
            </head>
            <body>
                <h1>Bella Cucina</h1>
                <p>Authentic Italian dining in the heart of downtown since 1998.</p>

                <div class="info-block">
                    <p><strong>Address:</strong> 742 Evergreen Terrace, Suite 4, Springfield, IL 62704</p>
                    <p><strong>Phone:</strong> (217) 555-0142</p>
                    <p><strong>Hours:</strong> Tue–Sun 11:30 AM – 10:00 PM, Closed Mondays</p>
                    <p><strong>Reservations:</strong> Required for parties of 6+</p>
                </div>

                <h2>Our Menu Highlights</h2>
                <ul>
                    <li>Truffle Risotto — ${'$'}24</li>
                    <li>Osso Buco Milanese — ${'$'}32</li>
                    <li>Margherita Pizza (wood-fired) — ${'$'}16</li>
                    <li>Tiramisu — ${'$'}10</li>
                </ul>

                <h2>Frequently Asked Questions</h2>
                <div class="faq-item">
                    <button class="faq-q" onclick="toggle(this)">Do you accommodate dietary restrictions?</button>
                    <div class="faq-a" style="display:none">Yes. We offer gluten-free pasta and several vegan entrées. Please inform your server of any allergies.</div>
                </div>
                <div class="faq-item">
                    <button class="faq-q" onclick="toggle(this)">Is there parking available?</button>
                    <div class="faq-a" style="display:none">Free street parking is available on Evergreen Terrace. A paid garage is located one block east on Elm Street (${'$'}5 flat rate evenings).</div>
                </div>
                <div class="faq-item">
                    <button class="faq-q" onclick="toggle(this)">Do you host private events?</button>
                    <div class="faq-a" style="display:none">Our private dining room seats up to 30 guests. Contact events@bellacucina.example.com for availability and pricing.</div>
                </div>

                <p style="margin-top: 32px; color: #888; font-size: 12px;">© 2024 Bella Cucina LLC. All rights reserved.</p>

                <script>
                    function toggle(btn) {
                        var a = btn.nextElementSibling;
                        a.style.display = a.style.display === 'none' ? 'block' : 'none';
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
