package io.deepsearch.application.services

import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import io.deepsearch.domain.testing.IsolatedKoinTest
import org.koin.java.KoinJavaComponent.inject

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtpLatencyBreakdownTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    companion object {
        private const val BODY_CHECK_URL = "https://www.otandp.com/body-check/"
        private const val SPECIALISED_URL = "https://www.otandp.com/body-check/specialised-health-checks"
        private const val FAQ_URL = "https://www.otandp.com/faq/"
        private const val TARGET_LATENCY_MS = 10_000L
    }

    data class LatencyTestCase(
        val name: String,
        val url: String,
        val query: String
    )

    private val testCases = listOf(
        LatencyTestCase("Body check - Standard price", BODY_CHECK_URL,
            "What is the price of the OT&P Standard body check package?"),
        LatencyTestCase("Body check - Stress Test inclusion", BODY_CHECK_URL,
            "Which OT&P body check package includes a Stress Test (treadmill test)?"),
        LatencyTestCase("Specialised - Cardiovascular price", SPECIALISED_URL,
            "What is the price of the OT&P Cardiovascular Risk Package?"),
        LatencyTestCase("Specialised - Well Woman Gold", SPECIALISED_URL,
            "What is the price of the OT&P Well Woman Gold package?"),
        LatencyTestCase("FAQ - waiting list", FAQ_URL,
            "Is there a waiting list to register with OT&P medical practice?"),
        LatencyTestCase("FAQ - consultation duration", FAQ_URL,
            "How long are first-time GP consultations at OT&P?")
    )

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

    @Test
    fun `OTP latency breakdown`() = runBlocking {
        val allResults = mutableListOf<Pair<LatencyTestCase, AgenticPageSearchResult>>()

        for (tc in testCases) {
            println("\n${"#".repeat(80)}")
            println("  RUNNING: ${tc.name}")
            println("  URL: ${tc.url}")
            println("  QUERY: ${tc.query}")
            println("${"#".repeat(80)}")

            val result = agenticSearchService.searchWithinPage(
                url = tc.url,
                query = tc.query,
                sessionId = QuerySessionId("latency-${tc.name.replace(" ", "-")}")
            )
            allResults.add(tc to result)
            printCaseBreakdown(tc, result)
        }

        printAggregateReport(allResults)
    }

    private fun printCaseBreakdown(tc: LatencyTestCase, result: AgenticPageSearchResult) {
        val timing = result.timingBreakdown ?: run {
            println("  [WARNING] No timing breakdown available for ${tc.name}")
            return
        }

        val sep = "-".repeat(110)
        println()
        println("  LATENCY BREAKDOWN: ${tc.name}")
        println("  Total: ${"%.1f".format(timing.totalMs / 1000.0)}s | " +
                "Cookie: ${timing.cookieDismissMs}ms | " +
                "Initial load: ${timing.initialLoadMs}ms | " +
                "Iterations: ${timing.iterations.size}")
        println()
        println("  %-5s  %7s  %7s  %7s  %7s  %7s  %7s  %7s  %7s".format(
            "Iter", "Total", "Fetch", "Annot", "KwScan", "NavLLM", "Extract", "Actions", "ExtWall"
        ))
        println("  $sep")

        for (it in timing.iterations) {
            val extractionWallContribution = maxOf(0, it.extractionPipelineMs - it.navAgentMs)
            println("  %-5d  %6.1fs  %6.1fs  %6.1fs  %6.1fs  %6.1fs  %6.1fs  %6.1fs  %6.1fs".format(
                it.iteration,
                it.totalMs / 1000.0,
                it.fetchMs / 1000.0,
                it.annotateMs / 1000.0,
                it.keywordScanMs / 1000.0,
                it.navAgentMs / 1000.0,
                it.extractionPipelineMs / 1000.0,
                it.actionsMs / 1000.0,
                extractionWallContribution / 1000.0
            ))
        }

        println("  $sep")

        val iterCount = timing.iterations.size
        if (iterCount > 0) {
            val avgFetch = timing.iterations.map { it.fetchMs }.average()
            val avgAnnotate = timing.iterations.map { it.annotateMs }.average()
            val avgKwScan = timing.iterations.map { it.keywordScanMs }.average()
            val avgNav = timing.iterations.map { it.navAgentMs }.average()
            val avgExtraction = timing.iterations.map { it.extractionPipelineMs }.average()
            val avgActions = timing.iterations.map { it.actionsMs }.average()
            val avgTotal = timing.iterations.map { it.totalMs }.average()

            println("  %-5s  %6.1fs  %6.1fs  %6.1fs  %6.1fs  %6.1fs  %6.1fs  %6.1fs".format(
                "AVG",
                avgTotal / 1000.0,
                avgFetch / 1000.0,
                avgAnnotate / 1000.0,
                avgKwScan / 1000.0,
                avgNav / 1000.0,
                avgExtraction / 1000.0,
                avgActions / 1000.0
            ))
        }

        val meetsTarget = timing.totalMs <= TARGET_LATENCY_MS
        println()
        println("  ${if (meetsTarget) "PASS" else "FAIL"}: ${timing.totalMs}ms " +
                "(target: <${TARGET_LATENCY_MS}ms)")

        val clicks = result.actionsPerformed.count { it.action is NavigationAction.Click }
        val scrolls = result.actionsPerformed.count { it.action is NavigationAction.ScrollAt }
        println("  Actions: ${result.actionsPerformed.size} total ($clicks clicks, $scrolls scrolls)")
        println("  Answer: ${result.answer?.take(120) ?: "(null)"}")
    }

    private fun printAggregateReport(allResults: List<Pair<LatencyTestCase, AgenticPageSearchResult>>) {
        val sep = "=".repeat(120)

        println("\n\n$sep")
        println("  OT&P LATENCY BREAKDOWN AGGREGATE REPORT")
        println(sep)

        val resultsWithTiming = allResults.filter { it.second.timingBreakdown != null }
        if (resultsWithTiming.isEmpty()) {
            println("  No timing data available.")
            return
        }

        val allIterations = resultsWithTiming.flatMap { it.second.timingBreakdown!!.iterations }
        val totalIterations = allIterations.size
        val totalCases = resultsWithTiming.size

        println("\n  PHASE BREAKDOWN (across $totalIterations iterations from $totalCases cases)")
        println("  ${"-".repeat(80)}")

        val sumFetch = allIterations.sumOf { it.fetchMs }
        val sumAnnotate = allIterations.sumOf { it.annotateMs }
        val sumKwScan = allIterations.sumOf { it.keywordScanMs }
        val sumNav = allIterations.sumOf { it.navAgentMs }
        val sumExtraction = allIterations.sumOf { it.extractionPipelineMs }
        val sumActions = allIterations.sumOf { it.actionsMs }
        val sumIterTotal = allIterations.sumOf { it.totalMs }

        val phases = listOf(
            "Fetch (screenshot+DOM)" to sumFetch,
            "Annotate (img proc)" to sumAnnotate,
            "Keyword scan" to sumKwScan,
            "Nav agent LLM" to sumNav,
            "Extraction pipeline" to sumExtraction,
            "Action execution" to sumActions
        )

        val phasesTotal = phases.sumOf { it.second }
        for ((name, ms) in phases) {
            val pct = if (phasesTotal > 0) ms * 100.0 / phasesTotal else 0.0
            val avg = ms.toDouble() / totalIterations
            println("    %-25s  %7.1fs total  %6.0fms avg  %5.1f%%".format(
                name, ms / 1000.0, avg, pct
            ))
        }

        val bottleneck = phases.maxByOrNull { it.second }
        println("\n  BOTTLENECK: ${bottleneck?.first} " +
                "(${"%.1f".format((bottleneck?.second ?: 0) / 1000.0)}s total, " +
                "${"%.1f".format((bottleneck?.second ?: 0).toDouble() * 100.0 / phasesTotal.coerceAtLeast(1))}%)")

        println("\n  PER-CASE SUMMARY")
        println("  ${"-".repeat(80)}")
        println("  %-40s  %7s  %5s  %7s  %6s".format(
            "Case", "Total", "Iters", "Avg/Iter", "Target"
        ))

        var passCount = 0
        for ((tc, result) in resultsWithTiming) {
            val t = result.timingBreakdown!!
            val meetsTarget = t.totalMs <= TARGET_LATENCY_MS
            if (meetsTarget) passCount++
            val avgPerIter = if (t.iterations.isNotEmpty())
                t.iterations.map { it.totalMs }.average() else 0.0
            println("  %-40s  %6.1fs  %5d  %6.1fs  %6s".format(
                tc.name.take(40),
                t.totalMs / 1000.0,
                t.iterations.size,
                avgPerIter / 1000.0,
                if (meetsTarget) "PASS" else "FAIL"
            ))
        }

        val allTotalMs = resultsWithTiming.map { it.second.timingBreakdown!!.totalMs }
        val avgCaseMs = allTotalMs.average()
        val avgIters = resultsWithTiming.map { it.second.timingBreakdown!!.iterations.size }.average()
        val allCookieMs = resultsWithTiming.map { it.second.timingBreakdown!!.cookieDismissMs }
        val allInitLoadMs = resultsWithTiming.map { it.second.timingBreakdown!!.initialLoadMs }

        println("\n  OVERALL")
        println("  ${"-".repeat(80)}")
        println("  Cases meeting <${TARGET_LATENCY_MS / 1000}s target: $passCount / $totalCases")
        println("  Avg total per case:     %6.1fs".format(avgCaseMs / 1000.0))
        println("  Avg iterations/case:    %6.1f".format(avgIters))
        println("  Avg per-iteration:      %6.1fs".format(sumIterTotal.toDouble() / totalIterations / 1000.0))
        println("  Avg cookie dismiss:     %6.0fms".format(allCookieMs.average()))
        println("  Avg initial load:       %6.0fms".format(allInitLoadMs.average()))
        println("  Min case time:          %6.1fs".format(allTotalMs.min() / 1000.0))
        println("  Max case time:          %6.1fs".format(allTotalMs.max() / 1000.0))
        println("  Median case time:       %6.1fs".format(
            allTotalMs.sorted().let { it[it.size / 2] } / 1000.0
        ))
        println()

        val avgNavMs = sumNav.toDouble() / totalIterations
        val avgFetchMs = sumFetch.toDouble() / totalIterations
        val avgExtMs = sumExtraction.toDouble() / totalIterations
        println("  TO REACH <${TARGET_LATENCY_MS / 1000}s TARGET:")
        println("  Current avg per-iteration: ${"%.0f".format(sumIterTotal.toDouble() / totalIterations)}ms")
        println("  If avg ${"%.0f".format(avgIters)} iterations, need ~${"%.0f".format(TARGET_LATENCY_MS / avgIters)}ms per iteration")
        println("  Nav agent LLM avg: ${"%.0f".format(avgNavMs)}ms (${"%.0f".format(avgNavMs * 100.0 / (sumIterTotal.toDouble() / totalIterations))}% of iteration)")
        println("  Fetch avg: ${"%.0f".format(avgFetchMs)}ms")
        println("  Extraction avg: ${"%.0f".format(avgExtMs)}ms (parallel with nav, wall contribution varies)")
        println()
        println(sep)
    }
}
