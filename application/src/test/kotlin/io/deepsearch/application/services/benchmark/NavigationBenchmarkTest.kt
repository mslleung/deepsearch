package io.deepsearch.application.services.benchmark

import com.google.genai.Client
import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.application.services.IAgenticWebpageSearchService
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication

/**
 * Navigation Agent Benchmark Suite.
 *
 * Runs all benchmark cases (controlled + real-world) against the
 * AgenticWebpageSearchService and produces pass/fail scoring via LLM judge.
 *
 * Requirements:
 * - deepsearch-browser running at localhost:8090
 * - GOOGLE_API_KEY environment variable
 *
 * Run individually:
 * - `controlled pages benchmark` for fast, deterministic tests
 * - `real-world pages benchmark` for live-site integration tests
 * - `full benchmark suite` for everything
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NavigationBenchmarkTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val genaiClient by inject<Client>()
    private val applicationScope by inject<IApplicationCoroutineScope>()
    private lateinit var runner: NavigationBenchmarkRunner

    @BeforeAll
    fun setup() {
        koinApp = koinApplication {
            modules(applicationBenchmarkTestModule)
        }
        koinApp.createEagerInstances()
        testKoin = koinApp.koin

        runner = NavigationBenchmarkRunner(agenticSearchService, genaiClient, maxConcurrency = 15)
    }

    @AfterAll
    fun teardown() {
        applicationScope.close()
        koinApp.close()
    }

    @Test
    fun `controlled pages benchmark`() = runBlocking {
        val cases = ControlledPageBenchmarks.all()
        val report = runner.runAll(cases)

        println(report.toMarkdown())

        assertTrue(
            report.passRate >= 0.70,
            "Controlled pages pass rate should be >= 70%, was ${"%.0f".format(report.passRate * 100)}%"
        )
    }

    @Test
    fun `real-world pages benchmark`() = runBlocking {
        val cases = RealWorldBenchmarks.all()
        val report = runner.runAll(cases)

        println(report.toMarkdown())

        assertTrue(
            report.passRate >= 0.60,
            "Real-world pages pass rate should be >= 60%, was ${"%.0f".format(report.passRate * 100)}%"
        )
    }

    @Test
    fun `full benchmark suite`() = runBlocking {
        val allCases = ControlledPageBenchmarks.all() + RealWorldBenchmarks.all()
        val report = runner.runAll(allCases)

        println(report.toMarkdown())

        assertTrue(
            report.passRate >= 0.65,
            "Full suite pass rate should be >= 65%, was ${"%.0f".format(report.passRate * 100)}%"
        )
    }

    @Test
    fun `sleekflow pricing benchmark`() = runBlocking {
        val cases = RealWorldBenchmarks.sleekFlow()
        val report = runner.runAll(cases)

        println(report.toMarkdown())
    }

    @Test
    fun `otp wellwoman gold`() = runBlocking {
        val cases = RealWorldBenchmarks.otpWellWomanGold()
        val report = runner.runAll(cases)

        println(report.toMarkdown())
    }

    @Test
    fun `otp hep-c accordion`() = runBlocking {
        val cases = RealWorldBenchmarks.otpHepC()
        val report = runner.runAll(cases)

        println(report.toMarkdown())
    }

    @Test
    fun `stripe radar fraud custom`() = runBlocking {
        val cases = RealWorldBenchmarks.stripeRadarFraud()
        val report = runner.runAll(cases)
        println(report.toMarkdown())
    }

    @Test
    fun `stripe faq discounts`() = runBlocking {
        val cases = RealWorldBenchmarks.stripeFaqDiscount()
        val report = runner.runAll(cases)
        println(report.toMarkdown())
    }

    @Test
    fun `notion faq student discount`() = runBlocking {
        val cases = RealWorldBenchmarks.notionStudentDiscount()
        val report = runner.runAll(cases)
        println(report.toMarkdown())
    }

    @Test
    fun `notion faq refund policy`() = runBlocking {
        val cases = RealWorldBenchmarks.notionRefundPolicy()
        val report = runner.runAll(cases)
        println(report.toMarkdown())
    }

    @Test
    fun `otp healthcare benchmark`() = runBlocking {
        val cases = RealWorldBenchmarks.otpHealthcare()
        val report = runner.runAll(cases)

        println(report.toMarkdown())
    }

    @Test
    fun `stripe pricing benchmark`() = runBlocking {
        val cases = RealWorldBenchmarks.stripe()
        val report = runner.runAll(cases)

        println(report.toMarkdown())
    }

    @Test
    fun `notion pricing benchmark`() = runBlocking {
        val cases = RealWorldBenchmarks.notion()
        val report = runner.runAll(cases)

        println(report.toMarkdown())
    }

    @Test
    fun `cloudflare plans benchmark`() = runBlocking {
        val cases = RealWorldBenchmarks.cloudflare()
        val report = runner.runAll(cases)

        println(report.toMarkdown())
    }

    @Test
    fun `hubspot pricing benchmark`() = runBlocking {
        val cases = RealWorldBenchmarks.hubspot()
        val report = runner.runAll(cases)

        println(report.toMarkdown())
    }

    @Test
    fun `github pricing benchmark`() = runBlocking {
        val cases = RealWorldBenchmarks.github()
        val report = runner.runAll(cases)

        println(report.toMarkdown())
    }

    @Test
    fun `intercom pricing benchmark`() = runBlocking {
        val cases = RealWorldBenchmarks.intercom()
        val report = runner.runAll(cases)

        println(report.toMarkdown())
    }

    @Test
    fun `cf-workers-overage repeat 5x`() = runBlocking {
        val case = RealWorldBenchmarks.cfWorkersOverage()
        val reports = mutableListOf<BenchmarkReport>()

        for (run in 1..5) {
            println("\n${"=".repeat(80)}")
            println("  RUN $run / 5: ${case.id}")
            println("${"=".repeat(80)}\n")

            val report = runner.runAll(listOf(case))
            reports.add(report)

            val sc = report.scoreCards.first()
            println("\n  >>> Run $run result: ${if (sc.pass) "PASS" else "FAIL"} | iters=${sc.actualIterations}")
        }

        println("\n${"=".repeat(80)}")
        println("  SUMMARY ACROSS 5 RUNS")
        println("${"=".repeat(80)}")
        reports.forEachIndexed { i, report ->
            val sc = report.scoreCards.first()
            println("  Run ${i + 1}: ${if (sc.pass) "PASS" else "FAIL"} | iters=${sc.actualIterations}")
        }
        val passCount = reports.count { it.scoreCards.first().pass }
        println("  Total: $passCount/5 passed")
        println("${"=".repeat(80)}")
    }
}
