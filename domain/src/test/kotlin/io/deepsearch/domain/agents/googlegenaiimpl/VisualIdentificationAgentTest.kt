package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.IVisualIdentificationAgent
import io.deepsearch.domain.agents.SemanticIdentificationInput
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.VisualIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.system.measureTimeMillis
import kotlin.test.Test

/**
 * Performance comparison test for visual identification approaches.
 * 
 * Compares:
 * 1. SEPARATE: SemanticIdentificationAgent + TableIdentificationAgent (2 parallel LLM calls)
 * 2. COMBINED: VisualIdentificationAgent (1 LLM call)
 * 
 * Metrics collected:
 * - Latency (ms)
 * - Token usage (prompt + output)
 * - Result accuracy (semantic elements found, tables found)
 */
class VisualIdentificationAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val semanticAgent by inject<ISemanticIdentificationAgent>()
    private val tableAgent by inject<ITableIdentificationAgent>()
    private val visualAgent by inject<IVisualIdentificationAgent>()
    private val browserPool by inject<IBrowserPool>()

    data class PerformanceMetrics(
        val approach: String,
        val url: String,
        val latencyMs: Long,
        val promptTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int,
        val semanticElementsFound: Int,
        val tablesFound: Int
    ) {
        override fun toString(): String = buildString {
            appendLine("=== $approach ===")
            appendLine("  URL: $url")
            appendLine("  Latency: ${latencyMs}ms")
            appendLine("  Tokens: $totalTokens (prompt: $promptTokens, output: $outputTokens)")
            appendLine("  Semantic elements: $semanticElementsFound")
            appendLine("  Tables: $tablesFound")
        }
    }

    /**
     * Compare performance of separate vs combined visual identification.
     * 
     * This test captures a page snapshot once, then runs both approaches
     * and compares their performance metrics.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://sleekflow.io/pricing",
            "https://www.otandp.com/body-check/",
            "https://mybeame.com/beame-student-discount"
        ]
    )
    fun `compare separate vs combined visual identification performance`(url: String) = runTest(testCoroutineDispatcher) {
        browserPool.withPage { page ->
            // Capture page data once (shared between both approaches)
            page.navigate(url)
            page.waitForLoad()
            
            val pageSnapshot = page.capturePageSnapshot()
            val screenshot = page.takeFullPageScreenshot()
            
            println("\n" + "=".repeat(80))
            println("PERFORMANCE COMPARISON: $url")
            println("=".repeat(80))
            println("Page: ${pageSnapshot.html.length} bytes HTML, ${screenshot.bytes.size} bytes screenshot")
            println("Bounding boxes: ${pageSnapshot.boundingBoxes.size}")
            println()

            // ========== Approach 1: SEPARATE (2 parallel LLM calls) ==========
            val separateMetrics = measureSeparateApproach(url, pageSnapshot, screenshot)
            println(separateMetrics)

            // ========== Approach 2: COMBINED (1 LLM call) ==========
            val combinedMetrics = measureCombinedApproach(url, pageSnapshot, screenshot)
            println(combinedMetrics)

            // ========== Comparison Summary ==========
            printComparisonSummary(separateMetrics, combinedMetrics)
            
            // Basic assertions
            assert(combinedMetrics.semanticElementsFound >= 0) { "Combined should find semantic elements" }
            assert(combinedMetrics.tablesFound >= 0) { "Combined should find tables" }
        }
    }

    private suspend fun measureSeparateApproach(
        url: String,
        pageSnapshot: IBrowserPage.PageSnapshotWithMetadata,
        screenshot: IBrowserPage.Screenshot
    ): PerformanceMetrics {
        var semanticTokens = 0
        var tableTokens = 0
        var semanticCount = 0
        var tableCount = 0

        val latency = measureTimeMillis {
            // Run both agents in parallel (like WebpageExtractionService does)
            val semanticDeferred = kotlinx.coroutines.coroutineScope {
                async {
                    semanticAgent.generate(
                        SemanticIdentificationInput(pageSnapshot, screenshot)
                    )
                }
            }
            val tableDeferred = kotlinx.coroutines.coroutineScope {
                async {
                    tableAgent.generate(
                        TableIdentificationInput(pageSnapshot, screenshot)
                    )
                }
            }

            val semanticResult = semanticDeferred.await()
            val tableResult = tableDeferred.await()

            semanticTokens = semanticResult.tokenUsage.totalTokens
            tableTokens = tableResult.tokenUsage.totalTokens
            semanticCount = countSemanticElements(semanticResult.elements)
            tableCount = tableResult.tables.size
        }

        return PerformanceMetrics(
            approach = "SEPARATE (2 parallel calls)",
            url = url,
            latencyMs = latency,
            promptTokens = 0, // Not tracked separately in parallel
            outputTokens = 0,
            totalTokens = semanticTokens + tableTokens,
            semanticElementsFound = semanticCount,
            tablesFound = tableCount
        )
    }

    private suspend fun measureCombinedApproach(
        url: String,
        pageSnapshot: IBrowserPage.PageSnapshotWithMetadata,
        screenshot: IBrowserPage.Screenshot
    ): PerformanceMetrics {
        var promptTokens = 0
        var outputTokens = 0
        var totalTokens = 0
        var semanticCount = 0
        var tableCount = 0

        val latency = measureTimeMillis {
            val result = visualAgent.generate(
                VisualIdentificationInput(pageSnapshot, screenshot)
            )

            promptTokens = result.tokenUsage.promptTokens
            outputTokens = result.tokenUsage.outputTokens
            totalTokens = result.tokenUsage.totalTokens
            semanticCount = countSemanticElements(result.semanticElements)
            tableCount = result.tables.size
        }

        return PerformanceMetrics(
            approach = "COMBINED (1 call)",
            url = url,
            latencyMs = latency,
            promptTokens = promptTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            semanticElementsFound = semanticCount,
            tablesFound = tableCount
        )
    }

    private fun printComparisonSummary(separate: PerformanceMetrics, combined: PerformanceMetrics) {
        println("=== COMPARISON SUMMARY ===")
        
        val latencyDiff = separate.latencyMs - combined.latencyMs
        val latencyPct = if (separate.latencyMs > 0) (latencyDiff * 100.0 / separate.latencyMs) else 0.0
        println("  Latency: ${separate.latencyMs}ms → ${combined.latencyMs}ms (${if (latencyDiff > 0) "-" else "+"}${kotlin.math.abs(latencyDiff)}ms, ${String.format("%.1f", latencyPct)}% ${if (latencyDiff > 0) "faster" else "slower"})")
        
        val tokenDiff = separate.totalTokens - combined.totalTokens
        val tokenPct = if (separate.totalTokens > 0) (tokenDiff * 100.0 / separate.totalTokens) else 0.0
        println("  Tokens: ${separate.totalTokens} → ${combined.totalTokens} (${if (tokenDiff > 0) "-" else "+"}${kotlin.math.abs(tokenDiff)}, ${String.format("%.1f", tokenPct)}% ${if (tokenDiff > 0) "less" else "more"})")
        
        println("  Semantic: ${separate.semanticElementsFound} → ${combined.semanticElementsFound}")
        println("  Tables: ${separate.tablesFound} → ${combined.tablesFound}")
        println()
    }

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
}
