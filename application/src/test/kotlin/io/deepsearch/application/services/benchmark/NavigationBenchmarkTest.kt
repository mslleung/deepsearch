package io.deepsearch.application.services.benchmark

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
 * AgenticWebpageSearchService and produces multi-dimensional scoring reports.
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
    private val applicationScope by inject<IApplicationCoroutineScope>()
    private lateinit var runner: NavigationBenchmarkRunner

    @BeforeAll
    fun setup() {
        koinApp = koinApplication {
            modules(applicationBenchmarkTestModule)
        }
        koinApp.createEagerInstances()
        testKoin = koinApp.koin

        runner = NavigationBenchmarkRunner(agenticSearchService)
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
            report.aggregateComposite >= 60.0,
            "Controlled pages composite score should be >= 60, was ${"%.1f".format(report.aggregateComposite)}"
        )
        assertTrue(
            report.successRate >= 0.70,
            "Controlled pages success rate should be >= 70%, was ${"%.0f".format(report.successRate * 100)}%"
        )
    }

    @Test
    fun `real-world pages benchmark`() = runBlocking {
        val cases = RealWorldBenchmarks.all()
        val report = runner.runAll(cases)

        println(report.toMarkdown())

        assertTrue(
            report.aggregateComposite >= 50.0,
            "Real-world pages composite score should be >= 50, was ${"%.1f".format(report.aggregateComposite)}"
        )
        assertTrue(
            report.successRate >= 0.60,
            "Real-world pages success rate should be >= 60%, was ${"%.0f".format(report.successRate * 100)}%"
        )
    }

    @Test
    fun `full benchmark suite`() = runBlocking {
        val allCases = ControlledPageBenchmarks.all() + RealWorldBenchmarks.all()
        val report = runner.runAll(allCases)

        println(report.toMarkdown())

        assertTrue(
            report.aggregateComposite >= 55.0,
            "Full suite composite score should be >= 55, was ${"%.1f".format(report.aggregateComposite)}"
        )
        assertTrue(
            report.successRate >= 0.65,
            "Full suite success rate should be >= 65%, was ${"%.0f".format(report.successRate * 100)}%"
        )
    }

    @Test
    fun `sleekflow pricing benchmark`() = runBlocking {
        val cases = RealWorldBenchmarks.sleekFlow()
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
}
