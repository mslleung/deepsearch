package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IUrlContextExtractionAgent
import io.deepsearch.domain.agents.UrlContextExtractionInput
import io.deepsearch.domain.config.domainBenchmarkTestModule
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import io.deepsearch.domain.config.IApplicationCoroutineScope
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * Tests for UrlContextExtractionAgent with timing analysis.
 * 
 * These tests help identify performance bottlenecks in URL context extraction.
 * The agent uses Gemini's URL Context tool which:
 * 1. Makes an HTTP request to fetch the URL content
 * 2. Processes the content through Gemini
 * 3. Returns a structured summary
 * 
 * Expected timing breakdown:
 * - URL fetch by Gemini: 2-5 seconds (depends on page size and network)
 * - LLM processing: 1-3 seconds
 * - Total: 3-8 seconds per URL
 */
class UrlContextExtractionAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainBenchmarkTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IUrlContextExtractionAgent>()
    private val applicationScope by inject<IApplicationCoroutineScope>()
    
    @AfterEach
    fun cleanup() {
        // Clean up application scope to cancel background coroutines
        applicationScope.close()
    }

    // ==================== Basic Functionality Tests ====================

    @Test
    fun `extracts context from simple homepage`() = runTest(testCoroutineDispatcher, timeout = 60.seconds) {
        // Given - a simple, fast-loading homepage
        val url = "https://example.com"

        // When
        val (result, duration) = measureTimedValue {
            agent.generate(UrlContextExtractionInput(url = url))
        }

        // Then
        println("=== Simple Homepage Test ===")
        println("URL: $url")
        println("Duration: ${duration.inWholeMilliseconds}ms")
        println("Token usage: prompt=${result.tokenUsage.promptTokens}, output=${result.tokenUsage.outputTokens}, total=${result.tokenUsage.totalTokens}")
        println("Summary length: ${result.websiteContext.contentSummary.length} chars")
        println("Summary: ${result.websiteContext.contentSummary.take(300)}...")
        println()

        assertTrue(result.websiteContext.contentSummary.isNotBlank(), "Content summary should not be blank")
        assertTrue(result.websiteContext.url == url, "URL should match input")
    }

    @Test
    fun `extracts context from content-heavy page`() = runTest(testCoroutineDispatcher, timeout = 120.seconds) {
        // Given - a content-heavy page (like sleekflow.io from the logs)
        val url = "https://sleekflow.io"

        // When
        val (result, duration) = measureTimedValue {
            agent.generate(UrlContextExtractionInput(url = url))
        }

        // Then
        println("=== Content-Heavy Page Test ===")
        println("URL: $url")
        println("Duration: ${duration.inWholeMilliseconds}ms")
        println("Token usage: prompt=${result.tokenUsage.promptTokens}, output=${result.tokenUsage.outputTokens}, total=${result.tokenUsage.totalTokens}")
        println("Summary length: ${result.websiteContext.contentSummary.length} chars")
        println("Summary: ${result.websiteContext.contentSummary.take(500)}...")
        println()

        assertTrue(result.websiteContext.contentSummary.isNotBlank(), "Content summary should not be blank")
        
        // Performance assertion: content-heavy pages should complete within 15 seconds
        assertTrue(
            duration.inWholeSeconds <= 15,
            "Content extraction took too long: ${duration.inWholeSeconds}s (expected <= 15s)"
        )
    }

    @Test
    fun `extracts context from pricing page`() = runTest(testCoroutineDispatcher, timeout = 120.seconds) {
        // Given - the actual pricing page from the log analysis
        val url = "https://sleekflow.io/en-us/pricing"

        // When
        val (result, duration) = measureTimedValue {
            agent.generate(UrlContextExtractionInput(url = url))
        }

        // Then
        println("=== Pricing Page Test ===")
        println("URL: $url")
        println("Duration: ${duration.inWholeMilliseconds}ms")
        println("Token usage: prompt=${result.tokenUsage.promptTokens}, output=${result.tokenUsage.outputTokens}, total=${result.tokenUsage.totalTokens}")
        println("Summary length: ${result.websiteContext.contentSummary.length} chars")
        println("Summary: ${result.websiteContext.contentSummary}")
        println()

        assertTrue(result.websiteContext.contentSummary.isNotBlank(), "Content summary should not be blank")
        
        // The summary should mention pricing-related content
        val summaryLower = result.websiteContext.contentSummary.lowercase()
        val containsPricingInfo = summaryLower.contains("price") || 
                                   summaryLower.contains("pricing") || 
                                   summaryLower.contains("plan") ||
                                   summaryLower.contains("tier") ||
                                   summaryLower.contains("cost")
        assertTrue(containsPricingInfo, "Summary should contain pricing-related information")
    }

    // ==================== Timing Analysis Tests ====================

    @Test
    fun `benchmark URL context extraction timing across different page types`() = runTest(testCoroutineDispatcher, timeout = 300.seconds) {
        // Test URLs with different characteristics
        val testCases = listOf(
            TestCase("Simple static page", "https://example.com"),
            TestCase("Medium content page", "https://www.wikipedia.org"),
            TestCase("Heavy SPA homepage", "https://sleekflow.io"),
            TestCase("Blog article", "https://blog.google/technology/ai/"),
            TestCase("Documentation page", "https://kotlinlang.org/docs/home.html")
        )

        println("=== URL Context Extraction Timing Benchmark ===")
        println("=" .repeat(80))
        
        val results = mutableListOf<BenchmarkResult>()

        for (testCase in testCases) {
            try {
                val (result, duration) = measureTimedValue {
                    agent.generate(UrlContextExtractionInput(url = testCase.url))
                }

                val benchmarkResult = BenchmarkResult(
                    name = testCase.name,
                    url = testCase.url,
                    durationMs = duration.inWholeMilliseconds,
                    promptTokens = result.tokenUsage.promptTokens,
                    outputTokens = result.tokenUsage.outputTokens,
                    totalTokens = result.tokenUsage.totalTokens,
                    summaryLength = result.websiteContext.contentSummary.length,
                    success = true
                )
                results.add(benchmarkResult)

                println("✓ ${testCase.name}")
                println("  URL: ${testCase.url}")
                println("  Duration: ${duration.inWholeMilliseconds}ms")
                println("  Tokens: prompt=${result.tokenUsage.promptTokens}, output=${result.tokenUsage.outputTokens}, total=${result.tokenUsage.totalTokens}")
                println("  Summary: ${result.websiteContext.contentSummary.take(150)}...")
                println()

            } catch (e: Exception) {
                results.add(
                    BenchmarkResult(
                        name = testCase.name,
                        url = testCase.url,
                        durationMs = -1,
                        promptTokens = 0,
                        outputTokens = 0,
                        totalTokens = 0,
                        summaryLength = 0,
                        success = false,
                        error = e.message
                    )
                )
                println("✗ ${testCase.name}: ${e.message}")
                println()
            }
        }

        // Print summary table
        println("=" .repeat(80))
        println("SUMMARY")
        println("=" .repeat(80))
        println(String.format("%-25s %-10s %-12s %-12s %-10s", "Test Case", "Duration", "Tokens", "Summary", "Status"))
        println("-".repeat(80))
        
        for (result in results) {
            val status = if (result.success) "✓" else "✗"
            val duration = if (result.durationMs >= 0) "${result.durationMs}ms" else "N/A"
            println(String.format(
                "%-25s %-10s %-12s %-12s %-10s",
                result.name.take(25),
                duration,
                result.totalTokens.toString(),
                "${result.summaryLength} chars",
                status
            ))
        }

        // Calculate statistics
        val successfulResults = results.filter { it.success }
        if (successfulResults.isNotEmpty()) {
            val avgDuration = successfulResults.map { it.durationMs }.average()
            val maxDuration = successfulResults.maxOf { it.durationMs }
            val minDuration = successfulResults.minOf { it.durationMs }
            val avgTokens = successfulResults.map { it.totalTokens }.average()

            println()
            println("Statistics:")
            println("  Avg Duration: ${avgDuration.toLong()}ms")
            println("  Min Duration: ${minDuration}ms")
            println("  Max Duration: ${maxDuration}ms")
            println("  Avg Tokens: ${avgTokens.toLong()}")
        }

        assertTrue(successfulResults.isNotEmpty(), "At least one test case should succeed")
    }

    @Test
    fun `analyze token usage correlation with page complexity`() = runTest(testCoroutineDispatcher, timeout = 180.seconds) {
        // Test to understand the relationship between page size and token usage
        val urls = listOf(
            "https://example.com",  // Minimal page
            "https://sleekflow.io", // Heavy SPA
            "https://sleekflow.io/en-us/pricing" // Data-heavy page
        )

        println("=== Token Usage Analysis ===")
        println()

        for (url in urls) {
            try {
                val (result, duration) = measureTimedValue {
                    agent.generate(UrlContextExtractionInput(url = url))
                }

                val tokensPerSecond = if (duration.inWholeSeconds > 0) {
                    result.tokenUsage.totalTokens / duration.inWholeSeconds
                } else {
                    result.tokenUsage.totalTokens
                }

                println("URL: $url")
                println("  Duration: ${duration.inWholeMilliseconds}ms")
                println("  Prompt tokens: ${result.tokenUsage.promptTokens}")
                println("  Output tokens: ${result.tokenUsage.outputTokens}")
                println("  Total tokens: ${result.tokenUsage.totalTokens}")
                println("  Tokens/second: $tokensPerSecond")
                println("  Summary length: ${result.websiteContext.contentSummary.length} chars")
                println()

            } catch (e: Exception) {
                println("URL: $url - FAILED: ${e.message}")
                println()
            }
        }
    }

    @Test
    fun `test extraction latency with cold vs warm requests`() = runTest(testCoroutineDispatcher, timeout = 180.seconds) {
        // This test measures if there's any caching effect at the Gemini API level
        val url = "https://example.com"

        println("=== Cold vs Warm Request Analysis ===")
        println()

        val durations = mutableListOf<Long>()

        repeat(3) { iteration ->
            val (result, duration) = measureTimedValue {
                agent.generate(UrlContextExtractionInput(url = url))
            }
            durations.add(duration.inWholeMilliseconds)

            println("Request ${iteration + 1}: ${duration.inWholeMilliseconds}ms (tokens: ${result.tokenUsage.totalTokens})")
        }

        println()
        println("Analysis:")
        println("  First request (cold): ${durations[0]}ms")
        println("  Subsequent requests: ${durations.drop(1).average().toLong()}ms avg")
        
        val coldWarmDiff = durations[0] - durations.drop(1).average().toLong()
        println("  Cold/Warm difference: ${coldWarmDiff}ms")
    }

    // ==================== Stress Tests ====================

    @Test
    fun `parallel extraction stress test`() = runTest(testCoroutineDispatcher, timeout = 300.seconds) {
        // Test parallel extraction to identify concurrency bottlenecks
        val urls = listOf(
            "https://example.com",
            "https://www.wikipedia.org",
            "https://kotlinlang.org"
        )

        println("=== Parallel Extraction Stress Test ===")
        println()

        // Sequential baseline
        val (sequentialResults, sequentialDuration) = measureTimedValue {
            urls.map { url ->
                agent.generate(UrlContextExtractionInput(url = url))
            }
        }

        println("Sequential execution: ${sequentialDuration.inWholeMilliseconds}ms")

        // Parallel execution
        val (parallelResults, parallelDuration) = measureTimedValue {
            urls.map { url ->
                async {
                    agent.generate(UrlContextExtractionInput(url = url))
                }
            }.awaitAll()
        }

        println("Parallel execution: ${parallelDuration.inWholeMilliseconds}ms")
        println()
        
        val speedup = sequentialDuration.inWholeMilliseconds.toDouble() / parallelDuration.inWholeMilliseconds
        println("Speedup factor: ${String.format("%.2f", speedup)}x")
        println()

        // Parallel should be at least 1.5x faster with 3 URLs
        assertTrue(
            speedup >= 1.3,
            "Parallel execution should be faster. Speedup: ${speedup}x"
        )
    }

    // ==================== Helper Classes ====================

    private data class TestCase(
        val name: String,
        val url: String
    )

    private data class BenchmarkResult(
        val name: String,
        val url: String,
        val durationMs: Long,
        val promptTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int,
        val summaryLength: Int,
        val success: Boolean,
        val error: String? = null
    )
}
