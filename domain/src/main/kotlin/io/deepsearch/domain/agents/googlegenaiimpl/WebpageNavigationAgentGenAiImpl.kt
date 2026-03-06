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
                    .description("What you observe on the current screenshot that is relevant to the query or open questions. Record data BEFORE it disappears due to your action (clicking/scrolling). Always populate this when relevant data is visible.")
                    .nullable(true)
                    .build(),
                "openQuestions" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("Specific questions that still need answering to provide a complete response. Empty array when all questions are resolved.")
                    .build(),
                "actionType" to Schema.builder()
                    .type("STRING")
                    .description("One of: click, click_at, scroll, search_text, peek_full_page, type, answer_found, give_up")
                    .enum_(listOf("click", "click_at", "scroll", "search_text", "peek_full_page", "type", "answer_found", "give_up"))
                    .build(),
                "labelNumber" to Schema.builder()
                    .type("INTEGER")
                    .description("The label number of the element to interact with. REQUIRED for 'click' and 'type' actions. Use -1 for other actions.")
                    .build(),
                "clickX" to Schema.builder()
                    .type("INTEGER")
                    .description("X coordinate (0-1000, left to right) in the screenshot. REQUIRED for click_at actions.")
                    .build(),
                "clickY" to Schema.builder()
                    .type("INTEGER")
                    .description("Y coordinate (0-1000, top to bottom) in the screenshot. REQUIRED for click_at actions.")
                    .build(),
                "scrollDirection" to Schema.builder()
                    .type("STRING")
                    .description("Scroll direction. Required when actionType is 'scroll'.")
                    .enum_(listOf("DOWN", "UP"))
                    .build(),
                "searchTerms" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("List of text phrases to search for on the page (like Ctrl+F). Tried in order, stops at first match. REQUIRED for 'search_text' actions.")
                    .build(),
                "text" to Schema.builder()
                    .type("STRING")
                    .description("Text to type into the focused element. REQUIRED for 'type' actions.")
                    .build(),
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Comprehensive answer synthesizing all findings. Required when actionType is 'answer_found'.")
                    .build(),
                "evidence" to Schema.builder()
                    .type("STRING")
                    .description("Evidence supporting the answer, or reason for giving up.")
                    .build(),
                "intention" to Schema.builder()
                    .type("STRING")
                    .description("Purpose of this webpage. Required when actionType is 'answer_found'.")
                    .build(),
                "contentDate" to Schema.builder()
                    .type("STRING")
                    .description("Publication or last-updated date visible on the page. Null if none visible.")
                    .nullable(true)
                    .build(),
                "reason" to Schema.builder()
                    .type("STRING")
                    .description("Brief explanation for the chosen action.")
                    .build()
            )
        )
        .required(listOf("actionType", "reason", "openQuestions", "labelNumber"))
        .build()

    private val systemInstruction = """
        You are a webpage search agent that ITERATIVELY gathers information to build a
        COMPREHENSIVE answer. You navigate a page, record findings, identify gaps, and
        explore to fill those gaps — like a researcher taking notes.

        You only see the CURRENT VIEWPORT — not the full page. Use search_text (Ctrl+F)
        to jump to relevant content, or scroll to explore. The viewport screenshot has
        numbered badges corresponding to ELEMENT_LABELS.

        ACTION_OUTCOME tells you whether your last action produced a visible change:
        - NO visible change → your click missed, try a DIFFERENT label number.
        - VISIBLE CHANGE → it worked. READ THE SCREENSHOT CAREFULLY for new data.
        - For search_text: tells you which term matched (or that none did).

        ANSWERED_QUESTIONS lists questions you have already resolved. Do not re-investigate them.
        OPEN_QUESTIONS lists questions that still need answering.

        === EVERY RESPONSE MUST INCLUDE ===

        1. "finding" — what you see on the CURRENT screenshot that is relevant to the
           query or to your open questions. READ THE SCREENSHOT — do not repeat old data
           from memory. Record data NOW because clicking or scrolling will change the
           viewport. Null only if nothing relevant is visible.

        2. "openQuestions" — specific questions that still need answering. Empty array
           only when the query is fully answered.

        3. An action — what to do next.

        === ACTIONS ===

        - search_text: Search the page text like Ctrl+F. Provide "searchTerms" — a list of
          phrases to try in order. The system scrolls the viewport to the first match.
          Use this FIRST when the answer is not visible — it is much faster than scrolling.
          Example: searchTerms=["promo code", "discount", "free trial"]
        - click: Click a labeled element. You MUST set "labelNumber" to the element's
          label number. Always prefer this over click_at when a label is available.
        - click_at: Click at specific coordinates in the screenshot. Use ONLY when the
          element you want to click has NO label number. Set "clickX" and "clickY" using
          the 0-1000 normalized coordinate system.
        - type: Click a labeled element and type text into it. Set "labelNumber" to the
          input element and "text" to what you want to type. Use when you see a search box
          or text input that could help find the answer.
        - scroll: Scroll up or down to see more content. Use when search_text didn't help
          and you need to explore nearby content.
        - peek_full_page: Capture an overview of the entire page. Use this to see the full
          page content and layout at a glance. Use as a last resort before give_up when
          search_text found nothing — the overview may reveal where the answer is located.
        - answer_found: Synthesize ALL your findings into a comprehensive answer.
          ONLY allowed when openQuestions is empty.
        - give_up: After exhausting all exploration options.

        === NAVIGATION STRATEGY ===

        Think like a human browsing a webpage:
        1. Look at the current viewport — is the answer visible? If yes, record and answer.
        2. Not visible? Use search_text with relevant keywords from the query.
        3. search_text found a match? Read the new viewport carefully.
        4. No text matches? Try typing in a search box if one exists.
        5. Still nothing? Use peek_full_page to see the full layout.
        6. Found a promising area in the peek? Scroll or search_text to get there.
        7. Exhausted all options? give_up.

        === ITERATIVE SEARCH LOOP ===

        Each iteration: READ the screenshot → RECORD what you see → IDENTIFY gaps → ACT.

        CRITICAL: After ACTION_OUTCOME says VISIBLE CHANGE, the page has NEW content.
        You MUST read the screenshot fresh — prices, text, and states may have changed.
        Do NOT carry forward old values from previous iterations.

        === RULES ===

        - ALWAYS read the screenshot fresh each iteration — the page may have changed.
        - ALWAYS record a finding BEFORE clicking/scrolling — data is lost on viewport change.
        - NEVER use answer_found while openQuestions is non-empty.
        - NEVER repeat a click that ACTION_OUTCOME said had no visible change.
          Try a DIFFERENT element or give_up on that specific question.
        - If a click fails 2+ times on the same element, abandon that question —
          set openQuestions to [] and use answer_found with what you have.
        - For tables/grids, carefully match row labels to column headers.
        - Prefer search_text over blind scrolling — it is faster and more precise.
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
        val evidence: String? = null,
        val intention: String? = null,
        val contentDate: String? = null,
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
                    NavigationAction.AnswerFound(
                        answer = answer,
                        evidence = response.evidence ?: "",
                        intention = response.intention ?: "Webpage",
                        contentDate = response.contentDate
                    )
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
