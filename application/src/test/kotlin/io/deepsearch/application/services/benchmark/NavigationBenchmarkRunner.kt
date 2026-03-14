package io.deepsearch.application.services.benchmark

import com.sun.net.httpserver.HttpServer
import io.deepsearch.application.services.AgenticPageSearchResult
import io.deepsearch.application.services.IAgenticWebpageSearchService
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

class NavigationBenchmarkRunner(
    private val agenticSearchService: IAgenticWebpageSearchService
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    data class CaseResult(
        val benchmarkCase: BenchmarkCase,
        val searchResult: AgenticPageSearchResult,
        val scoreCard: BenchmarkScoreCard,
        val durationMs: Long
    )

    suspend fun runAll(cases: List<BenchmarkCase>): BenchmarkReport {
        val (inlineCases, urlCases) = cases.partition { it.pageSource is PageSource.InlineHtml }

        val allResults = mutableListOf<CaseResult>()

        if (inlineCases.isNotEmpty()) {
            allResults.addAll(runInlineHtmlCases(inlineCases))
        }

        for (urlCase in urlCases) {
            allResults.add(runSingleCase(urlCase))
        }

        val scoreCards = allResults.map { it.scoreCard }
        val report = ActionEfficiencyAnalyzer.buildReport(scoreCards)

        printConsoleReport(allResults, report)

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

        val results = mutableListOf<CaseResult>()
        try {
            for (case in cases) {
                val source = case.pageSource as PageSource.InlineHtml
                val url = "http://localhost:$port${source.path}"
                results.add(runCaseWithUrl(case, url))
            }
        } finally {
            server.stop(0)
            logger.info("Benchmark HTTP server stopped")
        }

        return results
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
            throw e
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

    private fun printConsoleReport(results: List<CaseResult>, report: BenchmarkReport) {
        val separator = "=".repeat(100)

        println()
        println(separator)
        println("  NAVIGATION AGENT BENCHMARK REPORT")
        println(separator)
        println()

        println("%-35s  %5s  %5s  %5s  %5s  %5s  %5s  %6s".format(
            "Case", "Corr", "Effc", "Tool", "Anti", "COMP", "Iters", "Time"
        ))
        println("-".repeat(100))

        for (cr in results) {
            val sc = cr.scoreCard
            val status = if (sc.success) " " else "X"
            println(
                "%s %-33s  %5.1f  %5.1f  %5.1f  %5.1f  %5.1f  %2d/%-2d  %5.1fs".format(
                    status,
                    sc.caseId.take(33),
                    sc.correctnessScore,
                    sc.efficiencyScore,
                    sc.toolChoiceScore,
                    sc.antiPatternScore,
                    sc.compositeScore,
                    sc.actualIterations,
                    sc.optimalIterations,
                    cr.durationMs / 1000.0
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

        println("-".repeat(100))
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
        println(separator)
        println()
    }
}
