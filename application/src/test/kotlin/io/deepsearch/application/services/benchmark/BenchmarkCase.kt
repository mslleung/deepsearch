package io.deepsearch.application.services.benchmark

import io.deepsearch.domain.agents.NavigationAction
import kotlin.reflect.KClass

data class BenchmarkCase(
    val id: String,
    val description: String,
    val pageSource: PageSource,
    val query: String,
    val expectedOutcome: ExpectedOutcome,
    val idealActionSequence: List<KClass<out NavigationAction>>,
    val optimalIterations: Int,
    val constraints: BenchmarkConstraints
)

data class BenchmarkConstraints(
    val maxIterations: Int = 12,
    val requiredActionTypes: Set<KClass<out NavigationAction>> = emptySet(),
    val forbiddenActionTypes: Set<KClass<out NavigationAction>> = emptySet(),
    val maxClickCount: Int? = null
)

sealed class ExpectedOutcome {
    data class AnswerContains(
        val substrings: List<String>,
        val caseSensitive: Boolean = false
    ) : ExpectedOutcome()

    data object ShouldGiveUp : ExpectedOutcome()
}

sealed class PageSource {
    data class Url(val url: String) : PageSource()
    data class InlineHtml(val html: String, val path: String) : PageSource()
}

data class BenchmarkScoreCard(
    val caseId: String,
    val correctnessScore: Double,
    val efficiencyScore: Double,
    val toolChoiceScore: Double,
    val antiPatternScore: Double,
    val compositeScore: Double,
    val actualIterations: Int,
    val optimalIterations: Int,
    val success: Boolean,
    val answer: String?,
    val antiPatterns: List<AntiPattern>,
    val constraintViolations: List<String>,
    val actionSequenceSummary: String
) {
    companion object {
        const val CORRECTNESS_WEIGHT = 0.40
        const val EFFICIENCY_WEIGHT = 0.25
        const val TOOL_CHOICE_WEIGHT = 0.25
        const val ANTI_PATTERN_WEIGHT = 0.10

        fun computeComposite(
            correctness: Double,
            efficiency: Double,
            toolChoice: Double,
            antiPattern: Double
        ): Double = (correctness * CORRECTNESS_WEIGHT +
                efficiency * EFFICIENCY_WEIGHT +
                toolChoice * TOOL_CHOICE_WEIGHT +
                antiPattern * ANTI_PATTERN_WEIGHT)
    }
}

data class AntiPattern(
    val type: AntiPatternType,
    val description: String,
    val penaltyPoints: Double
)

enum class AntiPatternType {
    REPEATED_FAILED_CLICK,
    LABEL_HALLUCINATION,
    OFF_PAGE_RE_CLICK,
    PREMATURE_GIVE_UP_REJECTED,
    REDUNDANT_ACTION
}

data class BenchmarkReport(
    val scoreCards: List<BenchmarkScoreCard>,
    val aggregateCorrectness: Double,
    val aggregateEfficiency: Double,
    val aggregateToolChoice: Double,
    val aggregateAntiPattern: Double,
    val aggregateComposite: Double,
    val successRate: Double,
    val worstCases: List<BenchmarkScoreCard>
) {
    fun toMarkdown(): String = buildString {
        appendLine("# Navigation Agent Benchmark Report")
        appendLine()
        appendLine("## Aggregate Scores")
        appendLine()
        appendLine("| Dimension | Score |")
        appendLine("|-----------|-------|")
        appendLine("| Correctness (40%) | ${"%.1f".format(aggregateCorrectness)} |")
        appendLine("| Efficiency (25%) | ${"%.1f".format(aggregateEfficiency)} |")
        appendLine("| Tool Choice (25%) | ${"%.1f".format(aggregateToolChoice)} |")
        appendLine("| Anti-pattern (10%) | ${"%.1f".format(aggregateAntiPattern)} |")
        appendLine("| **Composite** | **${"%.1f".format(aggregateComposite)}** |")
        appendLine("| Success Rate | ${"%.0f".format(successRate * 100)}% (${scoreCards.count { it.success }}/${scoreCards.size}) |")
        appendLine()
        appendLine("## Per-Case Results")
        appendLine()
        appendLine("| Case | Correct | Iters (opt) | Composite | Answer |")
        appendLine("|------|---------|-------------|-----------|--------|")
        for (sc in scoreCards) {
            val correctMark = if (sc.success) "PASS" else "FAIL"
            val answerSnippet = sc.answer?.take(50)?.replace("|", "/") ?: "(none)"
            appendLine(
                "| ${sc.caseId} | $correctMark | ${sc.actualIterations} (${sc.optimalIterations}) " +
                        "| ${"%.1f".format(sc.compositeScore)} | $answerSnippet |"
            )
        }

        if (worstCases.isNotEmpty()) {
            appendLine()
            appendLine("## Worst Performing Cases")
            appendLine()
            for (sc in worstCases) {
                appendLine("### ${sc.caseId} (composite: ${"%.1f".format(sc.compositeScore)})")
                appendLine("- Actions: ${sc.actionSequenceSummary}")
                appendLine("- Iterations: ${sc.actualIterations} / optimal ${sc.optimalIterations}")
                if (sc.antiPatterns.isNotEmpty()) {
                    appendLine("- Anti-patterns:")
                    for (ap in sc.antiPatterns) {
                        appendLine("  - ${ap.type}: ${ap.description} (-${"%.0f".format(ap.penaltyPoints)})")
                    }
                }
                if (sc.constraintViolations.isNotEmpty()) {
                    appendLine("- Constraint violations:")
                    for (v in sc.constraintViolations) {
                        appendLine("  - $v")
                    }
                }
                appendLine()
            }
        }
    }
}
