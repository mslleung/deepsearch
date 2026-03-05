package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IVisualIdentificationAgent
import io.deepsearch.domain.agents.VisualIdentificationInput
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.system.measureTimeMillis
import kotlin.test.Test

/**
 * Tests for the combined visual identification agent.
 * 
 * VisualIdentificationAgent handles both semantic element detection and table detection
 * in a single vision-based LLM call.
 * 
 * Metrics collected:
 * - Latency (ms)
 * - Token usage (prompt + output)
 * - Result accuracy (semantic elements found, tables found)
 */
class VisualIdentificationAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val visualAgent by inject<IVisualIdentificationAgent>()
    private val browserPool by inject<IBrowserPool>()

    private fun countSemanticElements(elements: io.deepsearch.domain.models.valueobjects.SemanticElements): Int {
        var count = 0
        if (elements.header != null) count++
        if (elements.footer != null) count++
        if (elements.navSidebar != null) count++
        if (elements.breadcrumb != null) count++
        if (elements.cookieBanner != null) count++
        count += elements.adBanners.size
        count += elements.popups.size
        return count
    }

    /**
     * Test combined agent in isolation to verify it works correctly.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://example.com/",
            "https://sleekflow.io/pricing"
        ]
    )
    fun `combined visual identification produces valid results`(url: String) = runTest(testCoroutineDispatcher) {
        browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            
            val pageSnapshot = page.capturePageSnapshot()
            val screenshot = page.takeFullPageScreenshot()

            val result = visualAgent.generate(
                VisualIdentificationInput(pageSnapshot, screenshot)
            )

            println("Combined visual identification for $url:")
            println("  Semantic elements: ${countSemanticElements(result.semanticElements)}")
            println("    - Header: ${result.semanticElements.header?.dataId}")
            println("    - Footer: ${result.semanticElements.footer?.dataId}")
            println("    - NavSidebar: ${result.semanticElements.navSidebar?.dataId}")
            println("  Tables: ${result.tables.size}")
            result.tables.forEachIndexed { idx, table ->
                println("    [$idx] ${table.dataId}: ${table.auxiliaryInfo.take(50)}...")
            }
            println("  Token usage: ${result.tokenUsage.totalTokens}")

            // Basic validation
            assert(result.tokenUsage.totalTokens > 0) { "Should have token usage" }
        }
    }

    /**
     * Test that the closest match algorithm correctly identifies header elements
     * on pages with sidebars where the header doesn't span the full width.
     * 
     * Rust Docs scenario: The LLM identifies a full-width header strip, but the actual
     * #menu-bar element only covers the right portion (after the sidebar). The closest
     * match algorithm should prefer #menu-bar over larger containers like #body-container.
     */
    @Test
    fun `header mapping prefers closest match over larger containers - Rust Docs scenario`() = runTest(testCoroutineDispatcher) {
        browserPool.withPage { page ->
            // Rust docs has a sidebar layout where header doesn't span full width
            page.navigate("https://doc.rust-lang.org/book/")
            page.waitForLoad()

            val pageSnapshot = page.capturePageSnapshot()
            val screenshot = page.takeFullPageScreenshot()

            val result = visualAgent.generate(
                VisualIdentificationInput(pageSnapshot, screenshot)
            )

            println("Rust Docs visual identification:")
            println("  Header: ${result.semanticElements.header?.dataId} - ${result.semanticElements.header?.cssSelector}")
            println("  NavSidebar: ${result.semanticElements.navSidebar?.dataId} - ${result.semanticElements.navSidebar?.cssSelector}")

            // The header should NOT be mapped to body-container or other large containers
            // It should be mapped to a more specific header element
            val headerDataId = result.semanticElements.header?.dataId
            val headerCssSelector = result.semanticElements.header?.cssSelector ?: ""
            
            if (headerDataId != null) {
                // Verify header is not mapped to overly generic containers
                assert(!headerCssSelector.contains("body-container")) {
                    "Header should not be mapped to #body-container, got: $headerCssSelector"
                }
                assert(!headerCssSelector.contains("#content")) {
                    "Header should not be mapped to #content container, got: $headerCssSelector"
                }
                println("  ✓ Header correctly mapped to specific element: $headerCssSelector")
            }
        }
    }
}
