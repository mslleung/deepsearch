package io.deepsearch.application.services.benchmark

import com.sun.net.httpserver.HttpServer
import io.deepsearch.application.services.AgenticPageSearchResult
import io.deepsearch.application.services.IAgenticWebpageSearchService
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

class NavigationBenchmarkRunner(
    private val agenticSearchService: IAgenticWebpageSearchService,
    private val maxConcurrency: Int = 5
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    data class CaseResult(
        val benchmarkCase: BenchmarkCase,
        val searchResult: AgenticPageSearchResult,
        val scoreCard: BenchmarkScoreCard,
        val durationMs: Long
    )

    suspend fun runAll(cases: List<BenchmarkCase>): BenchmarkReport {
        val wallClockStart = System.currentTimeMillis()

        val (inlineCases, urlCases) = cases.partition { it.pageSource is PageSource.InlineHtml }
        val allResults = mutableListOf<CaseResult>()

        if (inlineCases.isNotEmpty()) {
            allResults.addAll(runInlineHtmlCases(inlineCases))
        }

        val semaphore = Semaphore(maxConcurrency)
        val urlResults = coroutineScope {
            urlCases.map { urlCase ->
                async {
                    semaphore.withPermit {
                        runSingleCase(urlCase)
                    }
                }
            }.awaitAll()
        }
        allResults.addAll(urlResults)

        val wallClockMs = System.currentTimeMillis() - wallClockStart
        val scoreCards = allResults.map { it.scoreCard }
        val report = ActionEfficiencyAnalyzer.buildReport(scoreCards)

        printConsoleReport(allResults, report, wallClockMs)

        return report
    }

    private suspend fun runInlineHtmlCases(cases: List<BenchmarkCase>): List<CaseResult> {
        val server = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
        val port = server.address.port

        for (case in cases) {
            val source = case.pageSource as PageSource.InlineHtml
            server.createContext(source.path) { exchange ->
                val html = source.html
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(html.toByteArray()) }
            }
        }

        server.start()
        logger.info("Benchmark HTTP server started on port {}", port)

        val semaphore = Semaphore(maxConcurrency)
        try {
            return coroutineScope {
                cases.map { case ->
                    async {
                        semaphore.withPermit {
                            val source = case.pageSource as PageSource.InlineHtml
                            val url = "http://localhost:$port${source.path}"
                            runCaseWithUrl(case, url)
                        }
                    }
                }.awaitAll()
            }
        } finally {
            server.stop(0)
            logger.info("Benchmark HTTP server stopped")
        }
    }

    private suspend fun runSingleCase(case: BenchmarkCase): CaseResult {
        val url = when (val source = case.pageSource) {
            is PageSource.Url -> source.url
            is PageSource.InlineHtml -> throw IllegalStateException(
                "InlineHtml cases should be batched via runInlineHtmlCases"
            )
        }
        return runCaseWithUrl(case, url)
    }

    private suspend fun runCaseWithUrl(case: BenchmarkCase, url: String): CaseResult {
        logger.info("Running benchmark: {} - {}", case.id, case.description)

        val startMs = System.currentTimeMillis()
        val result = try {
            agenticSearchService.searchWithinPage(
                url = url,
                query = case.query,
                sessionId = QuerySessionId("benchmark-${case.id}")
            )
        } catch (e: Exception) {
            logger.error("Benchmark case {} failed with exception: {}", case.id, e.message)
            AgenticPageSearchResult(
                answer = null,
                evidence = null,
                contentDate = null,
                actionsPerformed = emptyList(),
                observations = listOf("ERROR: ${e.message}"),
                success = false,
                totalTokenUsage = TokenUsageMetrics(modelName = "n/a", promptTokens = 0, outputTokens = 0, totalTokens = 0)
            )
        }
        val durationMs = System.currentTimeMillis() - startMs

        val scoreCard = ActionEfficiencyAnalyzer.score(case, result)

        logger.info(
            "Benchmark {} completed in {}ms — composite: {}, correctness: {}, efficiency: {}, tool: {}, antipattern: {}",
            case.id,
            durationMs,
            "%.1f".format(scoreCard.compositeScore),
            "%.1f".format(scoreCard.correctnessScore),
            "%.1f".format(scoreCard.efficiencyScore),
            "%.1f".format(scoreCard.toolChoiceScore),
            "%.1f".format(scoreCard.antiPatternScore)
        )

        return CaseResult(case, result, scoreCard, durationMs)
    }

    private fun printConsoleReport(results: List<CaseResult>, report: BenchmarkReport, wallClockMs: Long) {
        val separator = "=".repeat(120)

        println()
        println(separator)
        println("  NAVIGATION AGENT BENCHMARK REPORT")
        println(separator)
        println()

        println("%-35s  %5s  %5s  %5s  %5s  %5s  %5s  %6s  %7s  %7s  %7s".format(
            "Case", "Corr", "Effc", "Tool", "Anti", "COMP", "Iters", "Time", "Prompt", "Output", "Total"
        ))
        println("-".repeat(120))

        for (cr in results) {
            val sc = cr.scoreCard
            val tokens = cr.searchResult.totalTokenUsage
            val status = if (sc.success) " " else "X"
            println(
                "%s %-33s  %5.1f  %5.1f  %5.1f  %5.1f  %5.1f  %2d/%-2d  %5.1fs  %7d  %7d  %7d".format(
                    status,
                    sc.caseId.take(33),
                    sc.correctnessScore,
                    sc.efficiencyScore,
                    sc.toolChoiceScore,
                    sc.antiPatternScore,
                    sc.compositeScore,
                    sc.actualIterations,
                    sc.optimalIterations,
                    cr.durationMs / 1000.0,
                    tokens.promptTokens,
                    tokens.outputTokens,
                    tokens.totalTokens
                )
            )

            if (sc.constraintViolations.isNotEmpty()) {
                for (v in sc.constraintViolations) {
                    println("    VIOLATION: $v")
                }
            }
            if (sc.antiPatterns.isNotEmpty()) {
                for (ap in sc.antiPatterns) {
                    println("    ANTI-PATTERN: ${ap.type} — ${ap.description}")
                }
            }
        }

        println("-".repeat(120))
        println(
            "  %-33s  %5.1f  %5.1f  %5.1f  %5.1f  %5.1f".format(
                "AVERAGE",
                report.aggregateCorrectness,
                report.aggregateEfficiency,
                report.aggregateToolChoice,
                report.aggregateAntiPattern,
                report.aggregateComposite
            )
        )
        println()
        println("Success rate: ${"%.0f".format(report.successRate * 100)}% (${report.scoreCards.count { it.success }}/${report.scoreCards.size})")

        val totalDurationSec = results.sumOf { it.durationMs } / 1000.0
        val avgDurationSec = totalDurationSec / results.size
        val totalPromptTokens = results.sumOf { it.searchResult.totalTokenUsage.promptTokens }
        val totalOutputTokens = results.sumOf { it.searchResult.totalTokenUsage.outputTokens }
        val totalTokens = results.sumOf { it.searchResult.totalTokenUsage.totalTokens }
        val avgTokensPerCase = totalTokens / results.size
        val modelName = results.firstOrNull()?.searchResult?.totalTokenUsage?.modelName ?: "unknown"

        println()
        println(separator)
        println("  PERFORMANCE & TOKEN USAGE SUMMARY")
        println(separator)
        println()
        println("  Model: $modelName")
        println("  Cases run: ${results.size}")
        println()
        val wallClockSec = wallClockMs / 1000.0

        println("  Latency:")
        println("    Wall clock time:     %8.1fs".format(wallClockSec))
        println("    Sum of case times:   %8.1fs".format(totalDurationSec))
        if (totalDurationSec > 0) {
            println("    Parallelism factor:  %8.1fx".format(totalDurationSec / wallClockSec))
        }
        println("    Avg per case:        %8.1fs".format(avgDurationSec))
        println("    Min:                 %8.1fs".format(results.minOf { it.durationMs } / 1000.0))
        println("    Max:                 %8.1fs".format(results.maxOf { it.durationMs } / 1000.0))
        println("    Median:              %8.1fs".format(
            results.map { it.durationMs }.sorted().let { it[it.size / 2] } / 1000.0
        ))
        println()
        println("  Token Usage:")
        println("    Total prompt tokens: %8d".format(totalPromptTokens))
        println("    Total output tokens: %8d".format(totalOutputTokens))
        println("    Total tokens:        %8d".format(totalTokens))
        println("    Avg tokens/case:     %8d".format(avgTokensPerCase))
        println("    Avg prompt/case:     %8d".format(totalPromptTokens / results.size))
        println("    Avg output/case:     %8d".format(totalOutputTokens / results.size))
        println()
        println("  Iterations:")
        println("    Avg iterations:      %8.1f".format(results.map { it.scoreCard.actualIterations }.average()))
        println("    Avg optimal:         %8.1f".format(results.map { it.scoreCard.optimalIterations }.average()))
        println("    Tokens/iteration:    %8d".format(
            totalTokens / results.sumOf { it.scoreCard.actualIterations }.coerceAtLeast(1)
        ))
        println()
        println(separator)
        println()
    }
}
