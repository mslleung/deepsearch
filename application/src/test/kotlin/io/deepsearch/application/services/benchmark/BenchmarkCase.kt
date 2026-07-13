package io.deepsearch.application.services.benchmark

data class BenchmarkCase(
    val id: String,
    val description: String,
    val pageSource: PageSource,
    val query: String,
    val expectedOutcome: ExpectedOutcome,
    val constraints: BenchmarkConstraints = BenchmarkConstraints()
)

data class BenchmarkConstraints(
    val maxIterations: Int = 12
)

sealed class ExpectedOutcome {
    data class AnswerSatisfies(
        val criteria: String
    ) : ExpectedOutcome()

    data object ShouldGiveUp : ExpectedOutcome()
}

sealed class PageSource {
    data class Url(val url: String) : PageSource()
    data class InlineHtml(val html: String, val path: String) : PageSource()
}

data class BenchmarkScoreCard(
    val caseId: String,
    val pass: Boolean,
    val reasoning: String,
    val actualIterations: Int,
    val answer: String?,
    val actionSequenceSummary: String
)

data class BenchmarkReport(
    val scoreCards: List<BenchmarkScoreCard>,
    val passRate: Double,
    val failedCases: List<BenchmarkScoreCard>
) {
    fun toMarkdown(): String = buildString {
        appendLine("# Navigation Agent Benchmark Report")
        appendLine()
        appendLine("**Pass Rate: ${"%.0f".format(passRate * 100)}% (${scoreCards.count { it.pass }}/${scoreCards.size})**")
        appendLine()
        appendLine("## Per-Case Results")
        appendLine()
        appendLine("| Case | Result | Iters | Reasoning |")
        appendLine("|------|--------|-------|-----------|")
        for (sc in scoreCards) {
            val result = if (sc.pass) "PASS" else "FAIL"
            val reasonSnippet = if (sc.pass) "" else sc.reasoning.take(80).replace("|", "/")
            appendLine("| ${sc.caseId} | $result | ${sc.actualIterations} | $reasonSnippet |")
        }

        if (failedCases.isNotEmpty()) {
            appendLine()
            appendLine("## Failed Cases")
            appendLine()
            for (sc in failedCases) {
                appendLine("### ${sc.caseId}")
                appendLine("- **Reasoning**: ${sc.reasoning}")
                appendLine("- Actions: ${sc.actionSequenceSummary}")
                appendLine("- Iterations: ${sc.actualIterations}")
                val answerSnippet = sc.answer?.take(200) ?: "(no answer)"
                appendLine("- Answer snippet: $answerSnippet")
                appendLine()
            }
        }
    }
}
