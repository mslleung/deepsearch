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
 * Integration tests against Stranger Soccer Help Centre (https://strangersoccer.com/help)
 * to validate the agentic search pipeline against FAQ-style content with two distinct
 * structural challenges:
 *
 * 1. **Category listing pages** — FAQ questions are listed as links. The answers are NOT
 *    on the listing page; the agent must click a link to navigate to a separate article
 *    page. Since `searchWithinPage` navigates back on cross-page clicks, the agent
 *    should discover article URLs but may not be able to extract answers from the
 *    listing page alone. These tests validate graceful degradation.
 *
 * 2. **Individual article pages** — Each article page contains the full answer text
 *    directly on the page with no interaction needed beyond scrolling. These tests
 *    validate baseline content extraction from simple pages.
 *
 * Requires deepsearch-browser running at localhost:8090 and GOOGLE_API_KEY env var.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class StrangerSoccerHelpSearchTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

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

    // ==================== Group 1: Direct Article Pages (answer visible on page) ====================

    @Test
    fun `article - first game promo code`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = "https://strangersoccer.com/help/how-can-i-get-my-first-game-free",
            query = "What promo code do I use to get my first Stranger Soccer game free?",
            sessionId = QuerySessionId("ss-promo-code")
        )

        println("=== FIRST GAME PROMO CODE ===")
        printResult(result)

        assertTrue(result.success, "Should find promo code")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.uppercase().contains("WELCOME"),
            "Promo code should be WELCOME: ${result.answer}"
        )
    }

    @Test
    fun `article - membership cancellation policy`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = "https://strangersoccer.com/help/how-do-i-cancel-my-membership",
            query = "How far in advance do I need to cancel my Stranger Soccer membership before my billing date?",
            sessionId = QuerySessionId("ss-cancel-policy")
        )

        println("=== CANCELLATION POLICY ===")
        printResult(result)

        assertTrue(result.success, "Should find cancellation policy")
        assertNotNull(result.answer)
        assertTrue(
            result.answer!!.contains("72"),
            "Should mention 72 hours before billing date: ${result.answer}"
        )
    }

    @Test
    fun `article - credits refund policy`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = "https://strangersoccer.com/help/can-i-be-refunded-for-credits-in-my-account",
            query = "Can I get a refund for credits in my Stranger Soccer account?",
            sessionId = QuerySessionId("ss-refund")
        )

        println("=== CREDITS REFUND POLICY ===")
        printResult(result)

        assertTrue(result.success, "Should find refund policy")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("not refundable") || answer.contains("no") || answer.contains("cannot"),
            "Credits should not be refundable: ${result.answer}"
        )
        assertTrue(
            answer.contains("transfer"),
            "Should mention credits can be transferred: ${result.answer}"
        )
    }

    @Test
    fun `article - RSVP payment deadline`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = "https://strangersoccer.com/help/what-is-rsvp-payment-and-how-does-it-work",
            query = "How long do I have to make RSVP payment on Stranger Soccer, and what happens if I miss the deadline?",
            sessionId = QuerySessionId("ss-rsvp")
        )

        println("=== RSVP PAYMENT ===")
        printResult(result)

        assertTrue(result.success, "Should find RSVP payment info")
        assertNotNull(result.answer)
        val answer = result.answer!!
        assertTrue(answer.contains("24"), "Should mention 24 hour deadline: $answer")
        assertTrue(
            answer.lowercase().contains("removed") || answer.lowercase().contains("prevented"),
            "Should mention consequences of missing deadline: $answer"
        )
    }

    @Test
    fun `article - membership benefits`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = "https://strangersoccer.com/help/how-do-memberships-work",
            query = "What benefits do you get with a Stranger Soccer membership?",
            sessionId = QuerySessionId("ss-membership-benefits")
        )

        println("=== MEMBERSHIP BENEFITS ===")
        printResult(result)

        assertTrue(result.success, "Should find membership benefits")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("voucher") || answer.contains("discount"),
            "Should mention discount vouchers: ${result.answer}"
        )
        assertTrue(
            answer.contains("birthday"),
            "Should mention birthday reward: ${result.answer}"
        )
    }

    @Test
    fun `article - membership transferability`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = "https://strangersoccer.com/help/are-membership-benefits-transferrable",
            query = "Are Stranger Soccer membership benefits transferable to another person?",
            sessionId = QuerySessionId("ss-transfer")
        )

        println("=== MEMBERSHIP TRANSFERABILITY ===")
        printResult(result)

        assertTrue(result.success, "Should find transferability info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("not") || answer.contains("no") || answer.contains("only"),
            "Membership benefits should not be transferable: ${result.answer}"
        )
    }

    @Test
    fun `article - paying at the game`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = "https://strangersoccer.com/help/can-i-pay-at-the-game-itself",
            query = "Can I pay at the Stranger Soccer game itself?",
            sessionId = QuerySessionId("ss-pay-at-game")
        )

        println("=== PAY AT GAME ===")
        printResult(result)

        assertTrue(result.success, "Should find payment info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("no") || answer.contains("not") || answer.contains("do not"),
            "Should state that payment at the game is not accepted: ${result.answer}"
        )
    }

    @Test
    fun `article - GST and sales tax`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = "https://strangersoccer.com/help/does-gst-or-other-sales-tax-apply",
            query = "In which countries does GST or sales tax apply to Stranger Soccer purchases?",
            sessionId = QuerySessionId("ss-gst")
        )

        println("=== GST / SALES TAX ===")
        printResult(result)

        assertTrue(result.success, "Should find GST info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(
            answer.contains("singapore") || answer.contains("australia"),
            "Should mention Singapore and/or Australia: ${result.answer}"
        )
    }

    @Test
    fun `article - where is Stranger Soccer available`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = "https://strangersoccer.com/help/where-is-stranger-soccer-available",
            query = "Where was Stranger Soccer born, and how many cities is it available in?",
            sessionId = QuerySessionId("ss-availability")
        )

        println("=== AVAILABILITY ===")
        printResult(result)

        assertTrue(result.success, "Should find availability info")
        assertNotNull(result.answer)
        val answer = result.answer!!.lowercase()
        assertTrue(answer.contains("singapore"), "Should mention Singapore as birthplace: ${result.answer}")
    }

    // ==================== Group 2: Category Listing Pages (answers behind links) ====================
    //
    // Known limitation: Stranger Soccer's help center uses link-based navigation —
    // each FAQ question is a link to a separate article page. Since searchWithinPage
    // catches cross-page navigation and navigates back, the agent cannot follow these
    // links. However, it SHOULD discover the article URLs and record them.
    //
    // These tests validate that the agent:
    // - Identifies relevant FAQ question links on the category page
    // - Discovers the correct article URLs (even though it can't follow them)
    // - Gives up gracefully rather than looping indefinitely

    @Test
    fun `category page - discovers article URL for promo code`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = "https://strangersoccer.com/help?category=getting-started",
            query = "What promo code gives me my first Stranger Soccer game free?",
            sessionId = QuerySessionId("ss-cat-promo")
        )

        println("=== CATEGORY: FIRST GAME PROMO ===")
        printResult(result)
        println("Discovered URLs: ${result.discoveredUrls}")

        assertTrue(
            result.discoveredUrls.any { it.contains("first-game-free") },
            "Agent should discover the article URL for the first-game-free FAQ: ${result.discoveredUrls}"
        )
    }

    @Test
    fun `category page - discovers article URL for cancellation`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = "https://strangersoccer.com/help?category=membership",
            query = "How many hours before my billing date do I need to cancel my Stranger Soccer membership?",
            sessionId = QuerySessionId("ss-cat-cancel")
        )

        println("=== CATEGORY: CANCELLATION ===")
        printResult(result)
        println("Discovered URLs: ${result.discoveredUrls}")

        assertTrue(
            result.discoveredUrls.any { it.contains("cancel") },
            "Agent should discover the article URL for cancellation: ${result.discoveredUrls}"
        )
    }

    @Test
    fun `category page - discovers article URL for RSVP payment`() = runBlocking {
        val result = agenticSearchService.searchWithinPage(
            url = "https://strangersoccer.com/help?category=payment-matters",
            query = "What is the RSVP payment deadline on Stranger Soccer, and what happens if I miss it?",
            sessionId = QuerySessionId("ss-cat-rsvp")
        )

        println("=== CATEGORY: RSVP PAYMENT ===")
        printResult(result)
        println("Discovered URLs: ${result.discoveredUrls}")

        assertTrue(
            result.discoveredUrls.any { it.contains("rsvp") || it.contains("payment") },
            "Agent should discover the article URL for RSVP payment: ${result.discoveredUrls}"
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
        val clickCount = result.actionsPerformed.count { it.action is NavigationAction.Click }
        val findCount = result.actionsPerformed.count { it.action is NavigationAction.FindOnPage }
        val scrollToTextCount = result.actionsPerformed.count { it.action is NavigationAction.ScrollToText }
        val scrollCount = result.actionsPerformed.count { it.action is NavigationAction.Scroll }
        println("Clicks: $clickCount, Finds: $findCount, ScrollToText: $scrollToTextCount, Scrolls: $scrollCount")
        println("Token usage: prompt=${result.totalTokenUsage.promptTokens}, " +
                "output=${result.totalTokenUsage.outputTokens}, " +
                "total=${result.totalTokenUsage.totalTokens}")
    }
}
