package io.deepsearch.application.services

import com.sun.net.httpserver.HttpServer
import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotNull
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

        val clickCount = result.actionsPerformed.count { it is NavigationAction.Click }
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
        val clickCount = result.actionsPerformed.count { it is NavigationAction.Click }
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
        val clickCount = result.actionsPerformed.count { it is NavigationAction.Click }
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
        val clickCount = result.actionsPerformed.count { it is NavigationAction.Click }
        println("Click actions: $clickCount (VLM may find answer from text without clicking)")
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

    companion object {
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
    }
}
