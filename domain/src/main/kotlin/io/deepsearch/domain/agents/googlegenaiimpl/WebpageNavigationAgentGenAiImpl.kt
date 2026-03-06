package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel

import io.deepsearch.domain.agents.IWebpageNavigationAgent
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.agents.ScrollDirection
import io.deepsearch.domain.agents.WebpageNavigationInput
import io.deepsearch.domain.agents.WebpageNavigationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class WebpageNavigationAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IWebpageNavigationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "finding" to Schema.builder()
                    .type("STRING")
                    .description("Relevant data visible on the current screenshot. Record BEFORE acting.")
                    .nullable(true)
                    .build(),
                "openQuestions" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("Questions still needing answers. Empty when fully resolved.")
                    .build(),
                "actionType" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("click", "click_at", "scroll", "search_text", "peek_full_page", "type", "answer_found", "give_up"))
                    .build(),
                "labelNumber" to Schema.builder()
                    .type("INTEGER")
                    .description("Element label number for click/type actions.")
                    .nullable(true)
                    .build(),
                "clickX" to Schema.builder()
                    .type("INTEGER")
                    .description("X coordinate (0-1000) for click_at.")
                    .build(),
                "clickY" to Schema.builder()
                    .type("INTEGER")
                    .description("Y coordinate (0-1000) for click_at.")
                    .build(),
                "scrollDirection" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("DOWN", "UP"))
                    .build(),
                "searchTerms" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("Phrases to search (Ctrl+F style) for search_text.")
                    .build(),
                "text" to Schema.builder()
                    .type("STRING")
                    .description("Text to type for type actions.")
                    .build(),
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Final answer synthesizing all findings for answer_found.")
                    .build(),
                "reason" to Schema.builder()
                    .type("STRING")
                    .description("Brief reason for the chosen action.")
                    .build()
            )
        )
        .required(listOf("actionType", "openQuestions"))
        .build()

    private val systemInstruction = """
        You are a webpage search agent. Iteratively navigate, record findings, and explore to answer the query.

        You see only the CURRENT VIEWPORT. The screenshot has numbered badges matching ELEMENT_LABELS.
        ACTION_OUTCOME tells you if your last action caused a visible change. No change means try a different element.

        === RESPONSE ===
        1. "finding": what's relevant on the current screenshot. Record data BEFORE acting — viewport changes on click/scroll. Null only if nothing relevant is visible.
        2. "openQuestions": gaps remaining. Empty only when fully answered.
        3. One action (below).

        === ACTIONS ===
        - search_text: Ctrl+F page search. Set "searchTerms" (tried in order). Prefer this over scrolling.
        - click: Click labeled element. Set "labelNumber".
        - click_at: Click unlabeled element. Set "clickX"/"clickY" (0-1000).
        - type: Type into labeled input. Set "labelNumber" and "text".
        - scroll: Set "scrollDirection" (DOWN/UP). Use when search_text didn't help.
        - peek_full_page: Full-page overview. Last resort before give_up.
        - answer_found: Set "answer". ONLY when openQuestions is empty.
        - give_up: After exhausting all options.

        === RULES ===
        - Read the screenshot fresh each turn — never carry stale data forward.
        - If a click had no visible change, try a DIFFERENT element or approach.
        - For tables/grids, carefully match row labels to column headers.
    """.trimIndent()

    @Serializable
    private data class NavigationResponse(
        val finding: String? = null,
        val openQuestions: List<String>? = null,
        val actionType: String,
        val labelNumber: Int? = null,
        val clickX: Int? = null,
        val clickY: Int? = null,
        val scrollDirection: String? = null,
        val searchTerms: List<String>? = null,
        val text: String? = null,
        val answer: String? = null,
        val reason: String? = null
    )

    override suspend fun generate(input: WebpageNavigationInput): WebpageNavigationOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val userPrompt = buildString {
            appendLine("QUERY: ${input.query}")

            if (input.previousActionOutcome != null) {
                appendLine()
                appendLine("ACTION_OUTCOME:")
                appendLine(input.previousActionOutcome)
            }

            if (input.answeredQuestions.isNotEmpty()) {
                appendLine()
                appendLine("ANSWERED_QUESTIONS (already resolved — do NOT re-investigate):")
                input.answeredQuestions.forEachIndexed { idx, q ->
                    appendLine("  ${idx + 1}. $q")
                }
            }

            if (input.openQuestions.isNotEmpty()) {
                appendLine()
                appendLine("OPEN_QUESTIONS (must resolve before answer_found):")
                input.openQuestions.forEachIndexed { idx, q ->
                    appendLine("  ${idx + 1}. $q")
                }
            } else if (input.answeredQuestions.isNotEmpty()) {
                appendLine()
                appendLine("OPEN_QUESTIONS: None — all questions resolved. You may use answer_found.")
            }

            if (input.previousActions.isNotEmpty()) {
                appendLine()
                appendLine("PREVIOUS_ACTIONS:")
                input.previousActions.forEachIndexed { idx, action ->
                    appendLine("  ${idx + 1}. ${formatAction(action)}")
                }
            }

            if (input.elementLabels.isNotEmpty()) {
                appendLine()
                appendLine("ELEMENT_LABELS:")
                input.elementLabels.forEach { el ->
                    val roleStr = el.role?.let { " ($it)" } ?: ""
                    val statesStr = if (el.states.isNotEmpty()) " [${el.states.joinToString(", ")}]" else ""
                    appendLine("  [${el.labelNumber}] ${el.tag}$roleStr$statesStr: ${el.text.take(60)}")
                }
            }
        }

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<NavigationResponse>(this@WebpageNavigationAgentGenAiImpl::class.simpleName!!) {
                val contentParts = listOf(
                    Part.fromText("ANNOTATED_VIEWPORT:"),
                    Part.fromBytes(input.screenshot.bytes, input.screenshot.mimeType.value),
                    Part.fromText(userPrompt)
                )

                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(*contentParts.toTypedArray())),
                    GenerateContentConfig.builder()
                        .temperature(1.0F)
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                        .build()
                )

                result.checkFinishReason()

                result.usageMetadata().ifPresent { metadata ->
                    tokenUsage = TokenUsageMetrics(
                        modelName = modelId,
                        promptTokens = metadata.promptTokenCount().orElse(0),
                        outputTokens = metadata.candidatesTokenCount().orElse(0),
                        totalTokens = metadata.totalTokenCount().orElse(0)
                    )
                }

                result.text() ?: throw RuntimeException("No text response from model")
            }
        }

        val action = parseAction(response)
        logger.debug(
            "Navigation: {} | finding={} | openQ={} | reason={}",
            action::class.simpleName,
            response.finding?.take(80),
            response.openQuestions?.size ?: 0,
            response.reason
        )

        return WebpageNavigationOutput(
            action = action,
            finding = response.finding,
            openQuestions = response.openQuestions ?: emptyList(),
            tokenUsage = tokenUsage
        )
    }

    private fun parseAction(response: NavigationResponse): NavigationAction {
        return when (response.actionType) {
            "click" -> {
                val label = response.labelNumber?.takeIf { it >= 0 }
                    ?: extractLabelFromText(response.reason)
                if (label == null) {
                    logger.warn("VLM returned click without labelNumber, skipping")
                    NavigationAction.Scroll(direction = ScrollDirection.DOWN)
                } else {
                    NavigationAction.Click(labelNumber = label, reason = response.reason ?: "")
                }
            }
            "click_at" -> {
                val x = response.clickX
                val y = response.clickY
                if (x == null || y == null) {
                    logger.warn("VLM returned click_at without coordinates (x={}, y={}), scrolling instead", x, y)
                    NavigationAction.Scroll(direction = ScrollDirection.DOWN)
                } else {
                    NavigationAction.ClickAt(
                        x = x.coerceIn(0, 1000),
                        y = y.coerceIn(0, 1000),
                        reason = response.reason ?: ""
                    )
                }
            }
            "scroll" -> NavigationAction.Scroll(
                direction = when (response.scrollDirection?.uppercase()) {
                    "UP" -> ScrollDirection.UP
                    else -> ScrollDirection.DOWN
                }
            )
            "answer_found" -> {
                val answer = response.answer
                if (answer == null) {
                    logger.warn("VLM returned answer_found without answer text, treating as give_up")
                    NavigationAction.GiveUp(reason = "Model claimed answer_found but provided no answer")
                } else {
                    NavigationAction.AnswerFound(answer = answer)
                }
            }
            "search_text" -> {
                val terms = response.searchTerms
                if (terms.isNullOrEmpty()) {
                    logger.warn("VLM returned search_text without searchTerms, scrolling instead")
                    NavigationAction.Scroll(direction = ScrollDirection.DOWN)
                } else {
                    NavigationAction.SearchText(searchTerms = terms, reason = response.reason ?: "")
                }
            }
            "peek_full_page" -> NavigationAction.PeekFullPage(
                reason = response.reason ?: ""
            )
            "type" -> {
                val label = response.labelNumber?.takeIf { it >= 0 }
                val text = response.text
                if (label == null || text.isNullOrBlank()) {
                    logger.warn("VLM returned type without labelNumber or text, scrolling instead")
                    NavigationAction.Scroll(direction = ScrollDirection.DOWN)
                } else {
                    NavigationAction.Type(labelNumber = label, text = text, reason = response.reason ?: "")
                }
            }
            "give_up" -> NavigationAction.GiveUp(
                reason = response.reason ?: "No reason provided"
            )
            else -> NavigationAction.GiveUp(
                reason = "Unknown action type: ${response.actionType}"
            )
        }
    }

    private fun extractLabelFromText(text: String?): Int? {
        if (text == null) return null
        val match = Regex("""\blabel\s*(?:#\s*)?(\d+)\b""", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()?.also {
            logger.debug("Extracted labelNumber={} from reason text as fallback", it)
        }
    }

    private fun formatAction(action: NavigationAction): String = when (action) {
        is NavigationAction.Click -> {
            val target = action.elementDescription ?: "element"
            "Clicked $target — ${action.reason}"
        }
        is NavigationAction.ClickAt -> {
            val target = action.elementDescription ?: "unlabeled element"
            "Clicked at (${action.x},${action.y}) on $target — ${action.reason}"
        }
        is NavigationAction.Scroll -> "Scrolled ${action.direction.name.lowercase()}"
        is NavigationAction.SearchText -> "Searched for: ${action.searchTerms.joinToString()}"
        is NavigationAction.PeekFullPage -> "Peeked at full page overview"
        is NavigationAction.Type -> {
            val target = action.elementDescription ?: "element"
            "Typed '${action.text.take(30)}' into $target"
        }
        is NavigationAction.AnswerFound -> "Reported answer: ${action.answer.take(80)}"
        is NavigationAction.GiveUp -> "Gave up: ${action.reason}"
    }
}
