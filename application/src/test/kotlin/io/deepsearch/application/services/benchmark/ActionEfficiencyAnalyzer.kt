package io.deepsearch.application.services.benchmark

import io.deepsearch.application.services.AgenticPageSearchResult
import io.deepsearch.domain.agents.ActionWithOutcome
import io.deepsearch.domain.agents.NavigationAction
import kotlin.reflect.KClass

object ActionEfficiencyAnalyzer {

    data class EfficiencyReport(
        val findOnPageCount: Int,
        val scrollToTextCount: Int,
        val scrollCount: Int,
        val clickCount: Int,
        val scrollAtCount: Int,
        val typeCount: Int,
        val peekFullPageCount: Int,
        val totalIterations: Int,
        val optimalIterations: Int,
        val efficiencyRatio: Double,
        val wastedIterations: Int,
        val failedClickCount: Int,
        val offPageClickCount: Int
    )

    fun analyze(result: AgenticPageSearchResult, optimalIterations: Int): EfficiencyReport {
        val actions = result.actionsPerformed
        val findOnPageCount = actions.count { it.action is NavigationAction.FindOnPage }
        val scrollToTextCount = actions.count { it.action is NavigationAction.ScrollToText }
        val scrollCount = actions.count { it.action is NavigationAction.Scroll }
        val clickCount = actions.count { it.action is NavigationAction.Click }
        val scrollAtCount = actions.count { it.action is NavigationAction.ScrollAt }
        val typeCount = actions.count { it.action is NavigationAction.Type }
        val peekFullPageCount = actions.count { it.action is NavigationAction.PeekFullPage }
        val totalIterations = actions.size

        val failedClickCount = actions.count { entry ->
            entry.action is NavigationAction.Click &&
                    entry.outcome?.contains("NO visible change", ignoreCase = true) == true
        }
        val offPageClickCount = actions.count { entry ->
            entry.outcome?.contains("OFF-PAGE", ignoreCase = true) == true
        }

        val capped = optimalIterations.coerceAtLeast(1)
        val efficiencyRatio = if (totalIterations > 0) {
            (capped.toDouble() / totalIterations).coerceAtMost(1.0)
        } else 1.0

        val wastedIterations = (totalIterations - capped).coerceAtLeast(0)

        return EfficiencyReport(
            findOnPageCount = findOnPageCount,
            scrollToTextCount = scrollToTextCount,
            scrollCount = scrollCount,
            clickCount = clickCount,
            scrollAtCount = scrollAtCount,
            typeCount = typeCount,
            peekFullPageCount = peekFullPageCount,
            totalIterations = totalIterations,
            optimalIterations = capped,
            efficiencyRatio = efficiencyRatio,
            wastedIterations = wastedIterations,
            failedClickCount = failedClickCount,
            offPageClickCount = offPageClickCount
        )
    }

    fun printReport(report: EfficiencyReport, caseName: String) {
        println("\n=== Efficiency Report: $caseName ===")
        println("Iterations: ${report.totalIterations} (optimal: ${report.optimalIterations})")
        println("Efficiency ratio: ${"%.0f".format(report.efficiencyRatio * 100)}%")
        println("Wasted iterations: ${report.wastedIterations}")
        println("Actions: find_on_page=${report.findOnPageCount}, scroll_to_text=${report.scrollToTextCount}, " +
                "scroll=${report.scrollCount}, click=${report.clickCount}, scroll_at=${report.scrollAtCount}, " +
                "type=${report.typeCount}, peek=${report.peekFullPageCount}")
        println("Failed clicks: ${report.failedClickCount}, Off-page clicks: ${report.offPageClickCount}")
    }

    // --- Benchmark scoring API ---

    fun score(benchmarkCase: BenchmarkCase, result: AgenticPageSearchResult): BenchmarkScoreCard {
        val actions = result.actionsPerformed
        val actualIterations = actions.size

        val correctnessScore = scoreCorrectness(benchmarkCase.expectedOutcome, result)
        val efficiencyScore = scoreEfficiency(benchmarkCase.optimalIterations, actualIterations)
        val toolChoiceScore = scoreToolChoice(benchmarkCase.idealActionSequence, actions)
        val antiPatterns = detectAntiPatterns(actions)
        val antiPatternScore = scoreAntiPatterns(antiPatterns)
        val constraintViolations = checkConstraints(benchmarkCase.constraints, actions, result)

        val composite = BenchmarkScoreCard.computeComposite(
            correctnessScore, efficiencyScore, toolChoiceScore, antiPatternScore
        )

        val sequenceSummary = actions.joinToString(" -> ") { actionTypeName(it.action) }

        return BenchmarkScoreCard(
            caseId = benchmarkCase.id,
            correctnessScore = correctnessScore,
            efficiencyScore = efficiencyScore,
            toolChoiceScore = toolChoiceScore,
            antiPatternScore = antiPatternScore,
            compositeScore = composite,
            actualIterations = actualIterations,
            optimalIterations = benchmarkCase.optimalIterations,
            success = result.success || benchmarkCase.expectedOutcome is ExpectedOutcome.ShouldGiveUp && !result.success,
            answer = result.answer,
            antiPatterns = antiPatterns,
            constraintViolations = constraintViolations,
            actionSequenceSummary = sequenceSummary
        )
    }

    private fun scoreCorrectness(expected: ExpectedOutcome, result: AgenticPageSearchResult): Double {
        return when (expected) {
            is ExpectedOutcome.AnswerContains -> {
                if (!result.success || result.answer == null) return 0.0
                val answer = if (expected.caseSensitive) result.answer else result.answer.lowercase()
                val matched = expected.substrings.count { substring ->
                    val target = if (expected.caseSensitive) substring else substring.lowercase()
                    answer.contains(target)
                }
                (matched.toDouble() / expected.substrings.size) * 100.0
            }
            is ExpectedOutcome.ShouldGiveUp -> {
                when {
                    !result.success && result.answer == null -> 100.0
                    !result.success -> 80.0
                    else -> 0.0
                }
            }
        }
    }

    private fun scoreEfficiency(optimalIterations: Int, actualIterations: Int): Double {
        if (actualIterations == 0) return 100.0
        val optimal = optimalIterations.coerceAtLeast(1)
        return ((optimal.toDouble() / actualIterations) * 100.0).coerceAtMost(100.0)
    }

    /**
     * Compute tool choice score via longest common subsequence (LCS) of action types.
     * Normalized by ideal sequence length so the agent is rewarded for using the
     * right tools in roughly the right order.
     */
    private fun scoreToolChoice(
        idealSequence: List<KClass<out NavigationAction>>,
        actualActions: List<ActionWithOutcome>
    ): Double {
        if (idealSequence.isEmpty()) return 100.0

        val actualTypes = actualActions.map { it.action::class }
        val lcsLength = longestCommonSubsequence(idealSequence, actualTypes)
        return (lcsLength.toDouble() / idealSequence.size) * 100.0
    }

    private fun <T> longestCommonSubsequence(a: List<T>, b: List<T>): Int {
        val m = a.size
        val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp[m][n]
    }

    private fun detectAntiPatterns(actions: List<ActionWithOutcome>): List<AntiPattern> {
        val patterns = mutableListOf<AntiPattern>()

        val failedClickGroups = actions
            .filter { entry ->
                entry.action is NavigationAction.Click &&
                        entry.outcome?.contains("NO visible change", ignoreCase = true) == true
            }
            .groupBy { entry ->
                val a = entry.action as NavigationAction.Click
                a.elementDescription ?: "(${a.x},${a.y})"
            }
        for ((desc, entries) in failedClickGroups) {
            if (entries.size >= 2) {
                patterns.add(
                    AntiPattern(
                        AntiPatternType.REPEATED_FAILED_CLICK,
                        "Clicked '$desc' ${entries.size} times with no visible change",
                        15.0 * (entries.size - 1)
                    )
                )
            }
        }

        val labelErrors = actions.count { entry ->
            entry.outcome?.contains("does NOT exist", ignoreCase = true) == true
        }
        if (labelErrors > 0) {
            patterns.add(
                AntiPattern(
                    AntiPatternType.LABEL_HALLUCINATION,
                    "$labelErrors action(s) referenced non-existent labels",
                    20.0 * labelErrors
                )
            )
        }

        val scrollCount = actions.count { it.action is NavigationAction.Scroll }
        val hasScrollToText = actions.any { it.action is NavigationAction.ScrollToText }
        if (scrollCount >= 4 && !hasScrollToText) {
            patterns.add(
                AntiPattern(
                    AntiPatternType.EXCESSIVE_SCROLLING,
                    "$scrollCount scroll actions without using scroll_to_text",
                    10.0 * (scrollCount - 3)
                )
            )
        }

        val offPageReClicks = actions
            .filter { entry ->
                entry.outcome?.contains("already navigates OFF this page", ignoreCase = true) == true
            }
        if (offPageReClicks.isNotEmpty()) {
            patterns.add(
                AntiPattern(
                    AntiPatternType.OFF_PAGE_RE_CLICK,
                    "${offPageReClicks.size} re-click(s) on known off-page elements",
                    15.0 * offPageReClicks.size
                )
            )
        }

        val prematureGiveUpRejections = actions.count { entry ->
            entry.action is NavigationAction.GiveUp &&
                    entry.outcome?.contains("REJECTED", ignoreCase = true) == true
        }
        if (prematureGiveUpRejections > 0) {
            patterns.add(
                AntiPattern(
                    AntiPatternType.PREMATURE_GIVE_UP_REJECTED,
                    "$prematureGiveUpRejections premature give_up attempt(s) rejected",
                    20.0 * prematureGiveUpRejections
                )
            )
        }

        return patterns
    }

    private fun scoreAntiPatterns(antiPatterns: List<AntiPattern>): Double {
        val totalPenalty = antiPatterns.sumOf { it.penaltyPoints }
        return (100.0 - totalPenalty).coerceIn(0.0, 100.0)
    }

    private fun checkConstraints(
        constraints: BenchmarkConstraints,
        actions: List<ActionWithOutcome>,
        result: AgenticPageSearchResult
    ): List<String> {
        val violations = mutableListOf<String>()
        val totalIterations = actions.size

        if (totalIterations > constraints.maxIterations) {
            violations.add("Exceeded max iterations: $totalIterations > ${constraints.maxIterations}")
        }

        val actualActionTypes = actions.map { it.action::class }.toSet()
        for (required in constraints.requiredActionTypes) {
            if (required !in actualActionTypes) {
                violations.add("Missing required action type: ${required.simpleName}")
            }
        }

        for (forbidden in constraints.forbiddenActionTypes) {
            if (forbidden in actualActionTypes) {
                violations.add("Used forbidden action type: ${forbidden.simpleName}")
            }
        }

        constraints.maxClickCount?.let { max ->
            val clicks = actions.count { it.action is NavigationAction.Click }
            if (clicks > max) {
                violations.add("Exceeded max click count: $clicks > $max")
            }
        }

        constraints.maxScrollCount?.let { max ->
            val scrolls = actions.count { it.action is NavigationAction.Scroll }
            if (scrolls > max) {
                violations.add("Exceeded max scroll count: $scrolls > $max")
            }
        }

        constraints.maxFindOnPageCount?.let { max ->
            val finds = actions.count { it.action is NavigationAction.FindOnPage }
            if (finds > max) {
                violations.add("Exceeded max find_on_page count: $finds > $max")
            }
        }

        return violations
    }

    private fun actionTypeName(action: NavigationAction): String = when (action) {
        is NavigationAction.Click -> "click(${action.x},${action.y})"
        is NavigationAction.Scroll -> "scroll_${action.scrollDirection.name.lowercase()}"
        is NavigationAction.ScrollAt -> "scroll_at(${action.x},${action.y})_${action.scrollDirection.name.lowercase()}"
        is NavigationAction.FindOnPage -> "find_on_page"
        is NavigationAction.ScrollToText -> "scroll_to_text"
        is NavigationAction.PeekFullPage -> "peek_full_page"
        is NavigationAction.Type -> "type(${action.x},${action.y})"
        is NavigationAction.AnswerFound -> "answer_found"
        is NavigationAction.GiveUp -> "give_up"
    }

    fun buildReport(scoreCards: List<BenchmarkScoreCard>): BenchmarkReport {
        if (scoreCards.isEmpty()) {
            return BenchmarkReport(
                scoreCards = emptyList(),
                aggregateCorrectness = 0.0,
                aggregateEfficiency = 0.0,
                aggregateToolChoice = 0.0,
                aggregateAntiPattern = 0.0,
                aggregateComposite = 0.0,
                successRate = 0.0,
                worstCases = emptyList()
            )
        }

        val avgCorrectness = scoreCards.map { it.correctnessScore }.average()
        val avgEfficiency = scoreCards.map { it.efficiencyScore }.average()
        val avgToolChoice = scoreCards.map { it.toolChoiceScore }.average()
        val avgAntiPattern = scoreCards.map { it.antiPatternScore }.average()
        val avgComposite = scoreCards.map { it.compositeScore }.average()
        val successRate = scoreCards.count { it.success }.toDouble() / scoreCards.size

        val worstCases = scoreCards
            .sortedBy { it.compositeScore }
            .take(5)

        return BenchmarkReport(
            scoreCards = scoreCards,
            aggregateCorrectness = avgCorrectness,
            aggregateEfficiency = avgEfficiency,
            aggregateToolChoice = avgToolChoice,
            aggregateAntiPattern = avgAntiPattern,
            aggregateComposite = avgComposite,
            successRate = successRate,
            worstCases = worstCases
        )
    }
}
