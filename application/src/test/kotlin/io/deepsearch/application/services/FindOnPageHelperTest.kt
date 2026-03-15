package io.deepsearch.application.services

import io.deepsearch.domain.agents.IWebpageNavigationAgent
import io.deepsearch.domain.agents.SearchKeywordsResult
import io.deepsearch.domain.agents.WebpageNavigationInput
import io.deepsearch.domain.agents.WebpageNavigationOutput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.services.ScreenshotAnnotationService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Unit tests for the FindOnPage helper methods in AgenticWebpageSearchService:
 * [pickAutoScrollTarget] and [buildFindOnPageOutcome].
 *
 * These test the scroll-target selection strategy and the outcome string formatting
 * that guide the VLM agent's next action.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindOnPageHelperTest {

    private val service = AgenticWebpageSearchService(
        browserPool = StubBrowserPool(),
        webpageNavigationAgent = StubNavigationAgent(),
        screenshotAnnotationService = ScreenshotAnnotationService(),
        tokenUsageService = TestLlmTokenUsageService(),
        pageTextSearchService = PageTextSearchService()
    )

    // ─── pickAutoScrollTarget ───

    @Test
    fun `pickAutoScrollTarget - prefers visible currency symbol`() {
        val counts = mapOf(
            "pricing" to IBrowserPage.TextMatchCounts(visible = 2, total = 3),
            "$" to IBrowserPage.TextMatchCounts(visible = 1, total = 1),
        )
        val result = service.pickAutoScrollTarget(counts, emptyMap(), listOf("pricing", "$"))
        assertEquals("$", result, "Should prioritize currency symbol over generic keyword")
    }

    @Test
    fun `pickAutoScrollTarget - falls back to first visible exact keyword`() {
        val counts = mapOf(
            "pricing" to IBrowserPage.TextMatchCounts(visible = 0, total = 2),
            "features" to IBrowserPage.TextMatchCounts(visible = 3, total = 5),
        )
        val result = service.pickAutoScrollTarget(counts, emptyMap(), listOf("pricing", "features"))
        assertEquals("features", result, "Should pick the first keyword with visible matches")
    }

    @Test
    fun `pickAutoScrollTarget - uses stemmed match when no exact visible`() {
        val counts = mapOf(
            "pricing" to IBrowserPage.TextMatchCounts(visible = 0, total = 0),
        )
        val stemmed = mapOf(
            "pricing" to listOf(
                TextMatch("All prices shown in USD", "context line", 1.5f)
            )
        )
        val result = service.pickAutoScrollTarget(counts, stemmed, listOf("pricing"))
        assertNotNull(result, "Should fall back to best stemmed match text")
        assertTrue(result!!.startsWith("All prices"), "Should use the matched text from stemmed result")
    }

    @Test
    fun `pickAutoScrollTarget - returns null when no matches at all`() {
        val counts = mapOf(
            "nonexistent" to IBrowserPage.TextMatchCounts(visible = 0, total = 0),
        )
        val result = service.pickAutoScrollTarget(counts, emptyMap(), listOf("nonexistent"))
        assertNull(result, "Should return null when there are no visible or stemmed matches")
    }

    @Test
    fun `pickAutoScrollTarget - only hidden matches, no stemmed, returns null`() {
        val counts = mapOf(
            "pricing" to IBrowserPage.TextMatchCounts(visible = 0, total = 3),
        )
        val result = service.pickAutoScrollTarget(counts, emptyMap(), listOf("pricing"))
        assertNull(result, "Hidden-only matches should not trigger auto-scroll (need expand/scroll_to_text)")
    }

    @Test
    fun `pickAutoScrollTarget - HK dollar symbol is recognized`() {
        val counts = mapOf(
            "HK$" to IBrowserPage.TextMatchCounts(visible = 2, total = 2),
            "pricing" to IBrowserPage.TextMatchCounts(visible = 1, total = 1),
        )
        val result = service.pickAutoScrollTarget(counts, emptyMap(), listOf("pricing", "HK$"))
        assertEquals("HK$", result, "Should recognize HK$ as a currency symbol")
    }

    @Test
    fun `pickAutoScrollTarget - keyword order preserved for visible matches`() {
        val counts = mapOf(
            "first" to IBrowserPage.TextMatchCounts(visible = 1, total = 1),
            "second" to IBrowserPage.TextMatchCounts(visible = 1, total = 1),
        )
        val result = service.pickAutoScrollTarget(counts, emptyMap(), listOf("first", "second"))
        assertEquals("first", result, "Should pick the first visible keyword in keyword order")
    }

    // ─── buildFindOnPageOutcome ───

    @Test
    fun `buildFindOnPageOutcome - basic match counts`() {
        val counts = mapOf(
            "pricing" to IBrowserPage.TextMatchCounts(visible = 2, total = 3),
        )
        val outcome = service.buildFindOnPageOutcome(counts, emptyMap(), listOf("pricing"), "pricing: 2 (1 hidden)")

        assertTrue(outcome.startsWith("Match counts —"), "Should start with match counts")
        assertTrue(outcome.contains("pricing: 2 (1 hidden)"), "Should include the counts description")
    }

    @Test
    fun `buildFindOnPageOutcome - hidden only keywords get TIP`() {
        val counts = mapOf(
            "pricing" to IBrowserPage.TextMatchCounts(visible = 0, total = 5),
        )
        val outcome = service.buildFindOnPageOutcome(counts, emptyMap(), listOf("pricing"), "pricing: 0 (5 hidden)")

        assertTrue(outcome.contains("TIP:"), "Should include a TIP for hidden-only matches")
        assertTrue(outcome.contains("scroll_to_text"), "Should suggest scroll_to_text")
        assertTrue(outcome.contains("[collapsed]"), "Should suggest expanding collapsed elements")
    }

    @Test
    fun `buildFindOnPageOutcome - zero exact with stemmed matches, no extra noise`() {
        val counts = mapOf(
            "pricing" to IBrowserPage.TextMatchCounts(visible = 0, total = 0),
        )
        val stemmed = mapOf(
            "pricing" to listOf(
                TextMatch("All prices shown in USD", "surrounding context", 1.2f)
            )
        )
        val outcome = service.buildFindOnPageOutcome(counts, stemmed, listOf("pricing"), "pricing: 0")

        assertTrue(outcome.startsWith("Match counts — pricing: 0."), "Should show match counts")
        assertFalse(outcome.contains("No matches"), "Should NOT say 'no matches' when stemmed matches exist")
    }

    @Test
    fun `buildFindOnPageOutcome - zero matches for price keyword gives price TIP`() {
        val counts = mapOf(
            "pricing" to IBrowserPage.TextMatchCounts(visible = 0, total = 0),
        )
        val outcome = service.buildFindOnPageOutcome(counts, emptyMap(), listOf("pricing"), "pricing: 0")

        assertTrue(outcome.contains("currency symbols"), "Should suggest trying currency symbols for price queries")
    }

    @Test
    fun `buildFindOnPageOutcome - zero matches for non-price keyword`() {
        val counts = mapOf(
            "blockchain" to IBrowserPage.TextMatchCounts(visible = 0, total = 0),
        )
        val outcome = service.buildFindOnPageOutcome(counts, emptyMap(), listOf("blockchain"), "blockchain: 0")

        assertTrue(outcome.contains("Try different keywords"), "Should suggest trying different keywords")
    }

    @Test
    fun `buildFindOnPageOutcome - all visible, just match counts`() {
        val counts = mapOf(
            "pricing" to IBrowserPage.TextMatchCounts(visible = 3, total = 3),
            "features" to IBrowserPage.TextMatchCounts(visible = 2, total = 2),
        )
        val outcome = service.buildFindOnPageOutcome(
            counts, emptyMap(), listOf("pricing", "features"), "pricing: 3, features: 2"
        )

        assertEquals("Match counts — pricing: 3, features: 2.", outcome, "Should only contain match counts")
    }

    // ─── Stubs (unused by helper methods but required for constructor) ───

    private class StubBrowserPool : IBrowserPool {
        override suspend fun <T> withPage(proxyUrl: String?, block: suspend (IBrowserPage) -> T): T =
            throw UnsupportedOperationException("Stub")
    }

    private class StubNavigationAgent : IWebpageNavigationAgent {
        override suspend fun generate(input: WebpageNavigationInput): WebpageNavigationOutput =
            throw UnsupportedOperationException("Stub")
        override suspend fun generateSearchKeywords(query: String): SearchKeywordsResult =
            throw UnsupportedOperationException("Stub")
    }
}
