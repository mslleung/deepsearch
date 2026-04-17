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

/**
 * Multi-iteration OT&P benchmark: runs all 16 test cases [ITERATIONS] times
 * and prints an aggregated report with accuracy, latency, and token cost.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtpMultiIterationBenchmarkTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    companion object {
        private const val ITERATIONS = 5
        private const val BODY_CHECK_URL = "https://www.otandp.com/body-check/"
        private const val SPECIALISED_URL = "https://www.otandp.com/body-check/specialised-health-checks"
        private const val FAQ_URL = "https://www.otandp.com/faq/"
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

    data class TestCase(
        val name: String,
        val url: String,
        val query: String,
        val answerCheck: (String) -> Boolean
    )

    data class RunResult(
        val passed: Boolean,
        val answer: String?,
        val latencyMs: Long,
        val iterations: Int,
        val promptTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int,
        val clicks: Int,
        val scrollAts: Int,
        val error: String? = null
    )

    private val testCases = listOf(
        TestCase("Standard package price", BODY_CHECK_URL,
            "What is the price of the OT&P Standard body check package?")
            { it.contains("5,900") || it.contains("5900") },
        TestCase("Ultra package price", BODY_CHECK_URL,
            "What is the price of the OT&P Ultra body check package?")
            { it.contains("15,900") || it.contains("15900") },
        TestCase("All package prices", BODY_CHECK_URL,
            "List all OT&P body check packages and their prices.")
            { (it.contains("5,900") || it.contains("5900")) &&
              (it.contains("9,900") || it.contains("9900")) &&
              (it.contains("15,900") || it.contains("15900")) },
        TestCase("Stress Test inclusion", BODY_CHECK_URL,
            "Which OT&P body check package includes a Stress Test (treadmill test)?")
            { it.lowercase().contains("ultra") },
        TestCase("Consultation duration", BODY_CHECK_URL,
            "What is the initial consultation duration for the OT&P Standard body check package compared to the Comprehensive package?")
            { it.contains("45") && it.contains("60") },
        TestCase("ECG inclusion", BODY_CHECK_URL,
            "Is ECG included in all OT&P body check packages, or only in certain ones?")
            { val l = it.lowercase(); l.contains("all") || l.contains("every") || l.contains("each") ||
              (l.contains("standard") && l.contains("comprehensive") && l.contains("ultra")) },
        TestCase("Cardiovascular Risk price", SPECIALISED_URL,
            "What is the price of the OT&P Cardiovascular Risk Package?")
            { it.contains("4,900") || it.contains("4900") },
        TestCase("Cancer Risk contents", SPECIALISED_URL,
            "What tests are included in the OT&P Cancer Risk Package? List the specific tests.")
            { val l = it.lowercase(); l.contains("ultrasound") && (l.contains("cea") || l.contains("carcinoembryonic")) },
        TestCase("All specialised prices", SPECIALISED_URL,
            "List all specialised health check packages offered by OT&P and their prices.")
            { (it.contains("4,900") || it.contains("4900")) &&
              (it.contains("7,900") || it.contains("7900")) &&
              (it.contains("6,900") || it.contains("6900")) },
        TestCase("Well Woman Gold price", SPECIALISED_URL,
            "What is the price of the OT&P Well Woman Gold package?")
            { it.contains("9,900") || it.contains("9900") },
        TestCase("Well Woman Bronze features", SPECIALISED_URL,
            "Does the OT&P Well Woman Bronze package include a 3D Mammogram? What about a Pap Smear?")
            { val l = it.lowercase(); (l.contains("pap") || l.contains("smear")) && (l.contains("mammogram") || l.contains("3d")) },
        TestCase("Well Woman Silver vs Bronze", SPECIALISED_URL,
            "What tests are included in the OT&P Well Woman Silver package that are not in the Bronze package?")
            { val l = it.lowercase(); l.contains("hpv") || l.contains("pelvic") || l.contains("ultrasound") },
        TestCase("FAQ waiting list", FAQ_URL,
            "Is there a waiting list to register with OT&P medical practice?")
            { val l = it.lowercase(); l.contains("waiting list") || l.contains("register") },
        TestCase("FAQ consultation duration", FAQ_URL,
            "How long are first-time GP consultations at OT&P?")
            { val l = it.lowercase(); l.contains("consultation") || l.contains("minute") },
        TestCase("FAQ clinic locations", FAQ_URL,
            "How many clinics does OT&P have in Hong Kong?")
            { val l = it.lowercase(); l.contains("clinic") || l.contains("location") },
        TestCase("FAQ insurance billing", FAQ_URL,
            "Does OT&P have direct billing with insurance companies?")
            { val l = it.lowercase(); l.contains("insurance") || l.contains("billing") || l.contains("direct") }
    )

    @Test
    fun `OTP 16 cases x 5 iterations benchmark`() = runBlocking {
        val allResults = mutableMapOf<String, MutableList<RunResult>>()

        for (iteration in 1..ITERATIONS) {
            println("\n${"#".repeat(70)}")
            println("  ITERATION $iteration / $ITERATIONS")
            println("${"#".repeat(70)}")

            for (tc in testCases) {
                val result = runSingleCase(tc, iteration)
                allResults.getOrPut(tc.name) { mutableListOf() }.add(result)

                println("  [iter $iteration] ${tc.name}: ${if (result.passed) "PASS" else "FAIL"} | " +
                    "${result.latencyMs}ms | ${result.iterations} iters | ${result.totalTokens} tokens")
            }
        }

        printAggregateReport(allResults)
    }

    private suspend fun runSingleCase(tc: TestCase, iteration: Int): RunResult {
        val start = System.currentTimeMillis()
        return try {
            val result = agenticSearchService.searchWithinPage(
                url = tc.url,
                query = tc.query,
                sessionId = QuerySessionId("otp-bench-${tc.name.replace(" ", "-")}-iter$iteration")
            )
            val latencyMs = System.currentTimeMillis() - start
            val answer = result.answer ?: ""
            val passed = answer.isNotBlank() && tc.answerCheck(answer)
            val iters = result.actionsPerformed.size
            val clicks = result.actionsPerformed.count { it.action is NavigationAction.Click }
            val scrollAts = result.actionsPerformed.count { it.action is NavigationAction.ScrollAt }

            RunResult(
                passed = passed,
                answer = answer.take(200),
                latencyMs = latencyMs,
                iterations = iters,
                promptTokens = result.totalTokenUsage.promptTokens,
                outputTokens = result.totalTokenUsage.outputTokens,
                totalTokens = result.totalTokenUsage.totalTokens,
                clicks = clicks,
                scrollAts = scrollAts
            )
        } catch (e: Exception) {
            RunResult(
                passed = false,
                answer = null,
                latencyMs = System.currentTimeMillis() - start,
                iterations = 0,
                promptTokens = 0,
                outputTokens = 0,
                totalTokens = 0,
                clicks = 0,
                scrollAts = 0,
                error = e.message?.take(100)
            )
        }
    }

    private fun printAggregateReport(allResults: Map<String, List<RunResult>>) {
        val sep = "=".repeat(140)

        println("\n\n$sep")
        println("  OT&P MULTI-ITERATION BENCHMARK REPORT ($ITERATIONS iterations)")
        println(sep)

        println("\n%-35s  %5s  %8s  %8s  %8s  %8s  %8s  %5s  %5s".format(
            "Test Case", "Acc%", "Lat-Avg", "Lat-Min", "Lat-Max", "Tok-Avg", "Tok-Max", "Iters", "Clks"
        ))
        println("-".repeat(140))

        var totalPassed = 0
        var totalRuns = 0
        var totalLatency = 0L
        var totalTokens = 0L

        for (tc in testCases) {
            val runs = allResults[tc.name] ?: continue
            val passCount = runs.count { it.passed }
            val accuracy = passCount * 100.0 / runs.size
            val latencies = runs.map { it.latencyMs }
            val tokens = runs.map { it.totalTokens.toLong() }
            val iters = runs.map { it.iterations }
            val clicks = runs.map { it.clicks }

            totalPassed += passCount
            totalRuns += runs.size
            totalLatency += latencies.sum()
            totalTokens += tokens.sum()

            val status = if (passCount == runs.size) " " else "X"
            println(
                "%s %-33s  %4.0f%%  %7.1fs  %7.1fs  %7.1fs  %7dk  %7dk  %5.1f  %4.1f".format(
                    status,
                    tc.name.take(33),
                    accuracy,
                    latencies.average() / 1000.0,
                    latencies.min() / 1000.0,
                    latencies.max() / 1000.0,
                    tokens.average().toLong() / 1000,
                    tokens.max() / 1000,
                    iters.average(),
                    clicks.average()
                )
            )

            if (passCount < runs.size) {
                val failures = runs.filter { !it.passed }
                for ((idx, f) in failures.withIndex()) {
                    val reason = f.error ?: "answer: ${f.answer?.take(80) ?: "(null)"}"
                    println("    FAIL #${idx + 1}: $reason")
                }
            }
        }

        println("-".repeat(140))
        println("\n  SUMMARY:")
        println("  Overall accuracy: $totalPassed / $totalRuns (${"%.1f".format(totalPassed * 100.0 / totalRuns)}%)")
        println("  Average latency:  ${"%.1f".format(totalLatency.toDouble() / totalRuns / 1000.0)}s")
        println("  Total tokens:     ${totalTokens / 1000}k")
        println("  Avg tokens/run:   ${totalTokens / totalRuns / 1000}k")
        println(sep)
    }
}
