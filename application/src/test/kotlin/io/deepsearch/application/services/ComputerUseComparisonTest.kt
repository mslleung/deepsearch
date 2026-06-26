package io.deepsearch.application.services

import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import io.deepsearch.domain.testing.IsolatedKoinTest

/**
 * Side-by-side comparison of DeepSearch multi-agent pipeline vs Gemini 3.5 Flash Computer Use
 * on the same OTP Healthcare benchmark cases.
 *
 * Runs each case through both systems, collecting accuracy, latency, cost, and iteration metrics,
 * then prints a comparison report.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComputerUseComparisonTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val agenticSearchService by inject<IAgenticWebpageSearchService>()
    private val computerUseSearchService by inject<IComputerUseSearchService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    private val comparisonResults = mutableListOf<ComparisonResult>()

    companion object {
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
        printComparisonReport()
        applicationScope.close()
        koinApp.close()
    }

    private suspend fun runComparison(
        testName: String,
        url: String,
        query: String,
        answerCheck: (String) -> Boolean
    ) {
        println("\n${"=".repeat(70)}")
        println("COMPARISON TEST: $testName")
        println("URL: $url")
        println("QUERY: $query")
        println("=".repeat(70))

        // --- DeepSearch ---
        println("\n--- DeepSearch (multi-agent, ${ModelIds.GEMINI_3_1_FLASH_LITE.modelId}) ---")
        val dsStart = System.currentTimeMillis()
        val dsResult = try {
            agenticSearchService.searchWithinPage(
                url = url,
                query = query,
                sessionId = QuerySessionId("cu-cmp-ds-$testName")
            )
        } catch (e: Exception) {
            println("DeepSearch EXCEPTION: ${e.message}")
            null
        }
        val dsLatency = System.currentTimeMillis() - dsStart

        val dsAnswer = dsResult?.answer
        val dsPass = dsAnswer != null && answerCheck(dsAnswer)
        val dsTokens = dsResult?.totalTokenUsage ?: TokenUsageMetrics.empty()
        val dsCost = calculateCost(dsTokens)
        val dsIterations = dsResult?.actionsPerformed?.size ?: 0

        println("  Pass: $dsPass")
        println("  Latency: ${dsLatency}ms")
        println("  Cost: $${"%.6f".format(dsCost)}")
        println("  Tokens: prompt=${dsTokens.promptTokens}, output=${dsTokens.outputTokens}")
        println("  Iterations: $dsIterations")
        println("  Answer: ${dsAnswer?.take(200) ?: "NULL"}")

        // --- Computer Use ---
        println("\n--- Computer Use (single-model, ${ModelIds.GEMINI_3_5_FLASH.modelId}) ---")
        val cuStart = System.currentTimeMillis()
        val cuResult = try {
            computerUseSearchService.searchWithinPage(
                url = url,
                query = query
            )
        } catch (e: Exception) {
            println("Computer Use EXCEPTION: ${e.message}")
            null
        }
        val cuLatency = System.currentTimeMillis() - cuStart

        val cuAnswer = cuResult?.answer
        val cuPass = cuAnswer != null && answerCheck(cuAnswer)
        val cuTokens = cuResult?.totalTokenUsage ?: TokenUsageMetrics.empty()
        val cuCost = calculateCost(cuTokens)
        val cuIterations = cuResult?.iterations ?: 0

        println("  Pass: $cuPass")
        println("  Latency: ${cuLatency}ms")
        println("  Cost: $${"%.6f".format(cuCost)}")
        println("  Tokens: prompt=${cuTokens.promptTokens}, output=${cuTokens.outputTokens}")
        println("  Iterations: $cuIterations")
        println("  Actions: ${cuResult?.actionsPerformed?.joinToString(", ") ?: "NONE"}")
        println("  Answer: ${cuAnswer?.take(200) ?: "NULL"}")

        comparisonResults.add(
            ComparisonResult(
                testName = testName,
                dsAnswer = dsAnswer,
                dsPass = dsPass,
                dsLatencyMs = dsLatency,
                dsTokens = dsTokens,
                dsCost = dsCost,
                dsIterations = dsIterations,
                cuAnswer = cuAnswer,
                cuPass = cuPass,
                cuLatencyMs = cuLatency,
                cuTokens = cuTokens,
                cuCost = cuCost,
                cuIterations = cuIterations
            )
        )
    }

    // ==================== Group 1: Body Check Package Pricing ====================

    @Test
    fun `body check - Standard package price`() = runBlocking {
        runComparison("Standard price", BODY_CHECK_URL,
            "What is the price of the OT&P Standard body check package?"
        ) { it.contains("5,900") || it.contains("5900") }
    }

    @Test
    fun `body check - Ultra package price`() = runBlocking {
        runComparison("Ultra price", BODY_CHECK_URL,
            "What is the price of the OT&P Ultra body check package?"
        ) { it.contains("15,900") || it.contains("15900") }
    }

    @Test
    fun `body check - all package prices`() = runBlocking {
        runComparison("All prices", BODY_CHECK_URL,
            "List all OT&P body check packages and their prices."
        ) { it.contains("5,900") || it.contains("5900") }
    }

    // ==================== Group 2: Body Check Feature Comparison ====================

    @Test
    fun `body check - which package includes Stress Test`() = runBlocking {
        runComparison("Stress Test", BODY_CHECK_URL,
            "Which OT&P body check package includes a Stress Test (treadmill test)?"
        ) { it.lowercase().contains("ultra") }
    }

    @Test
    fun `body check - consultation duration for Standard vs Comprehensive`() = runBlocking {
        runComparison("Consultation duration", BODY_CHECK_URL,
            "What is the initial consultation duration for the OT&P Standard body check package compared to the Comprehensive package?"
        ) { it.contains("45") && it.contains("60") }
    }

    @Test
    fun `body check - ECG included in all packages`() = runBlocking {
        runComparison("ECG inclusion", BODY_CHECK_URL,
            "Is ECG included in all OT&P body check packages, or only in certain ones?"
        ) { answer ->
            val lower = answer.lowercase()
            lower.contains("all") || lower.contains("every") || lower.contains("each") ||
                    (lower.contains("standard") && lower.contains("comprehensive") && lower.contains("ultra"))
        }
    }

    // ==================== Group 3: Specialised Health Checks ====================

    @Test
    fun `specialised - Cardiovascular Risk Package price`() = runBlocking {
        runComparison("Cardiovascular price", SPECIALISED_URL,
            "What is the price of the OT&P Cardiovascular Risk Package?"
        ) { it.contains("4,900") || it.contains("4900") }
    }

    @Test
    fun `specialised - Cancer Risk Package contents`() = runBlocking {
        runComparison("Cancer Risk contents", SPECIALISED_URL,
            "What tests are included in the OT&P Cancer Risk Package? List the specific tests."
        ) { answer ->
            val lower = answer.lowercase()
            lower.contains("ultrasound") && (lower.contains("cea") || lower.contains("carcinoembryonic"))
        }
    }

    @Test
    fun `specialised - all package prices comparison`() = runBlocking {
        runComparison("All specialised prices", SPECIALISED_URL,
            "List all specialised health check packages offered by OT&P and their prices."
        ) { it.contains("4,900") || it.contains("4900") }
    }

    // ==================== Group 4: Well Woman Packages ====================

    @Test
    fun `well woman - Gold package price`() = runBlocking {
        runComparison("Well Woman Gold price", SPECIALISED_URL,
            "What is the price of the OT&P Well Woman Gold package?"
        ) { it.contains("9,900") || it.contains("9900") }
    }

    @Test
    fun `well woman - Bronze includes Pap Smear but not Mammogram`() = runBlocking {
        runComparison("Well Woman Bronze features", SPECIALISED_URL,
            "Does the OT&P Well Woman Bronze package include a 3D Mammogram? What about a Pap Smear?"
        ) { answer ->
            val lower = answer.lowercase()
            (lower.contains("pap") || lower.contains("smear")) &&
                    (lower.contains("mammogram") || lower.contains("3d"))
        }
    }

    @Test
    fun `well woman - difference between Silver and Bronze`() = runBlocking {
        runComparison("Well Woman Silver vs Bronze", SPECIALISED_URL,
            "What tests are included in the OT&P Well Woman Silver package that are not in the Bronze package?"
        ) { answer ->
            val lower = answer.lowercase()
            lower.contains("hpv") || lower.contains("pelvic") || lower.contains("ultrasound")
        }
    }

    // ==================== Group 5: FAQ Accordion ====================

    @Test
    fun `faq - waiting list to register`() = runBlocking {
        runComparison("FAQ waiting list", FAQ_URL,
            "Is there a waiting list to register with OT&P medical practice?"
        ) { answer ->
            val lower = answer.lowercase()
            lower.contains("waiting list") || lower.contains("register")
        }
    }

    @Test
    fun `faq - consultation duration`() = runBlocking {
        runComparison("FAQ consultation duration", FAQ_URL,
            "How long are first-time GP consultations at OT&P?"
        ) { answer ->
            val lower = answer.lowercase()
            lower.contains("consultation") || lower.contains("minute")
        }
    }

    @Test
    fun `faq - clinic locations count`() = runBlocking {
        runComparison("FAQ clinic locations", FAQ_URL,
            "How many clinics does OT&P have in Hong Kong?"
        ) { answer ->
            val lower = answer.lowercase()
            lower.contains("8") || lower.contains("eight") || lower.contains("clinic") || lower.contains("location")
        }
    }

    @Test
    fun `faq - insurance direct billing`() = runBlocking {
        runComparison("FAQ insurance billing", FAQ_URL,
            "Does OT&P have direct billing with insurance companies?"
        ) { answer ->
            val lower = answer.lowercase()
            lower.contains("insurance") || lower.contains("billing") || lower.contains("direct")
        }
    }

    // ==================== Helpers ====================

    private fun calculateCost(usage: TokenUsageMetrics): Double {
        val model = ModelIds.fromModelId(usage.modelName) ?: return 0.0
        val inputCost = (usage.promptTokens / 1_000_000.0) * model.inputPricePerMillion
        val outputCost = (usage.outputTokens / 1_000_000.0) * model.outputPricePerMillion
        return inputCost + outputCost
    }

    private fun printComparisonReport() {
        if (comparisonResults.isEmpty()) return

        val separator = "=".repeat(120)
        val thinSep = "-".repeat(120)

        println("\n\n$separator")
        println("  DeepSearch vs Computer Use — Comparison Report")
        println(separator)
        println()
        println(
            "%-30s | %-4s %8s %10s %6s | %-4s %8s %10s %6s".format(
                "Case", "Pass", "Latency", "Cost", "Iters",
                "Pass", "Latency", "Cost", "Iters"
            )
        )
        println(
            "%-30s | %-37s | %-37s".format(
                "", "DeepSearch (${ModelIds.GEMINI_3_1_FLASH_LITE.modelId})",
                "Computer Use (${ModelIds.GEMINI_3_5_FLASH.modelId})"
            )
        )
        println(thinSep)

        for (r in comparisonResults) {
            println(
                "%-30s | %-4s %7.1fs %9s %6d | %-4s %7.1fs %9s %6d".format(
                    r.testName.take(30),
                    if (r.dsPass) "PASS" else "FAIL",
                    r.dsLatencyMs / 1000.0,
                    "${"$"}${"%.4f".format(r.dsCost)}",
                    r.dsIterations,
                    if (r.cuPass) "PASS" else "FAIL",
                    r.cuLatencyMs / 1000.0,
                    "${"$"}${"%.4f".format(r.cuCost)}",
                    r.cuIterations
                )
            )
        }

        println(thinSep)

        val dsPassCount = comparisonResults.count { it.dsPass }
        val cuPassCount = comparisonResults.count { it.cuPass }
        val total = comparisonResults.size
        val dsAvgLatency = comparisonResults.map { it.dsLatencyMs }.average()
        val cuAvgLatency = comparisonResults.map { it.cuLatencyMs }.average()
        val dsTotalCost = comparisonResults.sumOf { it.dsCost }
        val cuTotalCost = comparisonResults.sumOf { it.cuCost }
        val dsAvgIters = comparisonResults.map { it.dsIterations }.average()
        val cuAvgIters = comparisonResults.map { it.cuIterations }.average()

        println(
            "%-30s | %-4s %7.1fs %9s %6.1f | %-4s %7.1fs %9s %6.1f".format(
                "TOTALS",
                "$dsPassCount/$total",
                dsAvgLatency / 1000.0,
                "${"$"}${"%.4f".format(dsTotalCost)}",
                dsAvgIters,
                "$cuPassCount/$total",
                cuAvgLatency / 1000.0,
                "${"$"}${"%.4f".format(cuTotalCost)}",
                cuAvgIters
            )
        )

        println(separator)

        // Token usage summary
        println("\nToken Usage Summary:")
        val dsTotalPrompt = comparisonResults.sumOf { it.dsTokens.promptTokens }
        val dsTotalOutput = comparisonResults.sumOf { it.dsTokens.outputTokens }
        val cuTotalPrompt = comparisonResults.sumOf { it.cuTokens.promptTokens }
        val cuTotalOutput = comparisonResults.sumOf { it.cuTokens.outputTokens }
        println("  DeepSearch: prompt=$dsTotalPrompt, output=$dsTotalOutput, total=${dsTotalPrompt + dsTotalOutput}")
        println("  Computer Use: prompt=$cuTotalPrompt, output=$cuTotalOutput, total=${cuTotalPrompt + cuTotalOutput}")

        // Winner analysis
        println("\nWinner Summary:")
        println("  Accuracy: ${if (dsPassCount > cuPassCount) "DeepSearch" else if (cuPassCount > dsPassCount) "Computer Use" else "Tie"} ($dsPassCount vs $cuPassCount)")
        println("  Latency:  ${if (dsAvgLatency < cuAvgLatency) "DeepSearch" else "Computer Use"} (${"%.1f".format(dsAvgLatency / 1000.0)}s vs ${"%.1f".format(cuAvgLatency / 1000.0)}s)")
        println("  Cost:     ${if (dsTotalCost < cuTotalCost) "DeepSearch" else "Computer Use"} ($${"%.4f".format(dsTotalCost)} vs $${"%.4f".format(cuTotalCost)})")
        println()
    }
}

private data class ComparisonResult(
    val testName: String,
    val dsAnswer: String?,
    val dsPass: Boolean,
    val dsLatencyMs: Long,
    val dsTokens: TokenUsageMetrics,
    val dsCost: Double,
    val dsIterations: Int,
    val cuAnswer: String?,
    val cuPass: Boolean,
    val cuLatencyMs: Long,
    val cuTokens: TokenUsageMetrics,
    val cuCost: Double,
    val cuIterations: Int
)
