package io.deepsearch.application.services.benchmark

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.application.services.AgenticPageSearchResult
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import org.slf4j.LoggerFactory

class ActionEfficiencyAnalyzer(
    private val client: Client
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val judgeSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "pass" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether the answer satisfies the criteria")
                    .build(),
                "reasoning" to Schema.builder()
                    .type("STRING")
                    .description("Explanation of why the answer passes or fails. On failure, describe what specific information is missing or incorrect.")
                    .build()
            )
        )
        .required(listOf("pass", "reasoning"))
        .build()

    suspend fun score(benchmarkCase: BenchmarkCase, result: AgenticPageSearchResult): BenchmarkScoreCard {
        val actions = result.actionsPerformed
        val actualIterations = actions.size
        val sequenceSummary = actions.joinToString(" -> ") { actionTypeName(it.action) }

        val (pass, reasoning) = when (val expected = benchmarkCase.expectedOutcome) {
            is ExpectedOutcome.AnswerSatisfies -> {
                judgeCorrectness(benchmarkCase.query, expected.criteria, result.answer)
            }
            is ExpectedOutcome.ShouldGiveUp -> {
                if (!result.success) {
                    Pair(true, "Correctly determined the information is not available")
                } else {
                    Pair(false, "Should have given up but reported success")
                }
            }
        }

        return BenchmarkScoreCard(
            caseId = benchmarkCase.id,
            pass = pass,
            reasoning = reasoning,
            actualIterations = actualIterations,
            answer = result.answer,
            actionSequenceSummary = sequenceSummary
        )
    }

    private suspend fun judgeCorrectness(
        query: String,
        criteria: String,
        actualAnswer: String?
    ): Pair<Boolean, String> {
        if (actualAnswer.isNullOrBlank()) {
            return Pair(false, "No answer was produced")
        }

        val prompt = """
            You are a benchmark judge evaluating whether a search system's answer satisfies the expected criteria.

            **Query**: $query
            **Expected criteria**: $criteria
            **Actual answer**: $actualAnswer

            Evaluate whether the actual answer contains the information described in the criteria. 
            Be lenient with formatting differences (e.g., "$5,900" vs "HK$5900" vs "5900"), 
            but strict about factual correctness.
            
            If the answer fails, explain exactly what information is missing or incorrect.
        """.trimIndent()

        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE.modelId

        return try {
            val response = retryLlmCall<JudgeResponse>("BenchmarkJudge") {
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(Part.fromText(prompt))),
                    GenerateContentConfig.builder()
                        .temperature(0.0F)
                        .responseSchema(judgeSchema)
                        .responseMimeType("application/json")
                        .build()
                )
                result.text() ?: throw RuntimeException("No text response from judge model")
            }
            Pair(response.pass, response.reasoning)
        } catch (e: Exception) {
            logger.error("LLM judge call failed for query '{}': {}", query, e.message)
            Pair(false, "LLM judge error: ${e.message}")
        }
    }

    fun buildReport(scoreCards: List<BenchmarkScoreCard>): BenchmarkReport {
        if (scoreCards.isEmpty()) {
            return BenchmarkReport(
                scoreCards = emptyList(),
                passRate = 0.0,
                failedCases = emptyList()
            )
        }

        val passRate = scoreCards.count { it.pass }.toDouble() / scoreCards.size
        val failedCases = scoreCards.filter { !it.pass }

        return BenchmarkReport(
            scoreCards = scoreCards,
            passRate = passRate,
            failedCases = failedCases
        )
    }

    private fun actionTypeName(action: NavigationAction): String = when (action) {
        is NavigationAction.Click -> "click(${action.elementLabel})_${action.target ?: "?"}"
        is NavigationAction.ScrollAt -> "scroll_at(${action.x},${action.y})_${action.scrollDirection.name.lowercase()}"
        is NavigationAction.Type -> "type(${action.x},${action.y})"
        is NavigationAction.ExplorationFinished -> "exploration_finished"
        is NavigationAction.GiveUp -> "give_up"
    }

    @kotlinx.serialization.Serializable
    private data class JudgeResponse(
        val pass: Boolean,
        val reasoning: String
    )
}
