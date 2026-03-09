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
                is NavigationAction.Click -> action.labelNumber >= 25
                is NavigationAction.Type -> action.labelNumber >= 25
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
    }
}
