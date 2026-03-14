package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel

import io.deepsearch.domain.agents.ActionWithOutcome
import io.deepsearch.domain.agents.CaptureRegion
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
                "thinking" to Schema.builder()
                    .type("STRING")
                    .description("Chain-of-thought reasoning: (1) what happened from your last action, (2) what you observe on the current screen, (3) your plan for the next action and why.")
                    .build(),
                "finding" to Schema.builder()
                    .type("STRING")
                    .description("Query-relevant data extracted from the current screenshot. Null only if nothing relevant is visible.")
                    .nullable(true)
                    .build(),
                "openQuestions" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("Questions still needing answers. Empty when fully resolved.")
                    .build(),
                "actionType" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("click", "click_at", "scroll", "find_on_page", "scroll_to_text", "peek_full_page", "type", "answer_found", "give_up"))
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
                "keywords" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("For find_on_page: keywords to search for. Returns visible and hidden match counts per keyword.")
                    .build(),
                "searchText" to Schema.builder()
                    .type("STRING")
                    .description("For scroll_to_text: the keyword to scroll to.")
                    .build(),
                "scrollDirection" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("DOWN", "UP"))
                    .description("For scroll: direction to scroll.")
                    .build(),
                "scrollPercent" to Schema.builder()
                    .type("INTEGER")
                    .description("For scroll: amount as percentage of viewport (10-100). Default 100.")
                    .build(),
                "text" to Schema.builder()
                    .type("STRING")
                    .description("Text to type for type actions.")
                    .build(),
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("ONLY for answer_found. Final answer synthesizing all findings. Must be null/omitted for all other action types.")
                    .nullable(true)
                    .build(),
                "reason" to Schema.builder()
                    .type("STRING")
                    .description("Brief reason for the chosen action.")
                    .build(),
                "captureRegions" to Schema.builder()
                    .type("ARRAY")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                mapOf(
                                    "x1" to Schema.builder().type("INTEGER").description("Left edge (0-1000).").build(),
                                    "y1" to Schema.builder().type("INTEGER").description("Top edge (0-1000).").build(),
                                    "x2" to Schema.builder().type("INTEGER").description("Right edge (0-1000).").build(),
                                    "y2" to Schema.builder().type("INTEGER").description("Bottom edge (0-1000).").build(),
                                    "relevance" to Schema.builder().type("STRING").description("Why this visual region is relevant to the query.").build()
                                )
                            )
                            .required(listOf("x1", "y1", "x2", "y2", "relevance"))
                            .build()
                    )
                    .description("Bounding boxes of visual regions (charts, diagrams, table images, etc.) relevant to the query. Use 0-1000 coordinates. Omit or leave empty if nothing visual is worth capturing.")
                    .nullable(true)
                    .build()
            )
        )
        .required(listOf("actionType", "thinking", "openQuestions"))
        .propertyOrdering(listOf(
            "actionType", "thinking", "finding", "openQuestions", "reason",
            "labelNumber", "clickX", "clickY", "keywords", "searchText",
            "scrollDirection", "scrollPercent", "text",
            "captureRegions", "answer"
        ))
        .build()

    private val systemInstruction = """
        You are a webpage exploration agent. You examine a single page to find information.

        You see the CURRENT VIEWPORT as an annotated screenshot. Numbered badges match ELEMENT_LABELS.

        === EACH TURN ===
        1. THINK — Set "thinking" with your chain-of-thought: what happened from your last action, what you observe on the current screen, and your plan for the next action. This is fed back to you on subsequent turns as your memory — be specific and useful to your future self.
        2. RECORD — Set "finding" with query-relevant data extracted from the current screenshot. Null only if nothing relevant is visible.
        3. ACT — Pick ONE action from the list below.

        === STRATEGY (follow this order — MANDATORY) ===
        On a new page, follow this preferred sequence:
        1. Check if the answer is already visible in the current viewport.
        2. ALWAYS use find_on_page FIRST (before scrolling or clicking) with relevant keywords to assess whether the page contains the information. This is your most important action — do NOT skip it.
        3. If find_on_page shows visible matches, use scroll_to_text to jump directly to them (much faster than scrolling).
        4. If find_on_page shows hidden matches, use scroll_to_text to jump to them (the browser reveals hidden content) or expand [collapsed] elements.
        5. Only use scroll as a last resort when find_on_page returned no matches but the page may have visual-only content (images, canvas elements).
        6. Expand [collapsed] accordions/tabs/dropdowns — the answer is often hidden behind them.
        7. Do NOT click off-page links as a first strategy. Explore the CURRENT page thoroughly first.

        === KEYWORD TIPS for find_on_page ===
        Choose keywords that match ACTUAL PAGE TEXT, not abstract concepts:
        - For prices: search "$", "HK$", "£", "€", or specific amounts like "5,900" — NOT "price" or "cost".
        - For scroll_to_text: prefer currency symbols over product names — product names appear in menus/headers far from prices, currency symbols appear in price tables.
        - For features: search the exact feature name, e.g. "Stress Test", "role-based".
        - If first keywords return 0 matches, try synonyms or shorter fragments.
        - Always include at least one highly specific keyword AND one broader keyword.

        === ACTIONS ===
        Interact:
        - click: Click a labeled element. Set "labelNumber". Elements marked [collapsed] hide content — click them to reveal what's inside before concluding info is absent.
        - click_at: Click an unlabeled element by coordinates. Set "clickX"/"clickY" (0–1000 scale).
        - type: Type into a labeled input. Set "labelNumber" and "text".

        Explore:
        - find_on_page: Search page text for keywords. Set "keywords" (list). Returns match counts per keyword. Hidden matches (e.g. "keyword: 0 (2 hidden)") mean the content exists behind collapsed/hidden elements — expand them or use scroll_to_text to navigate there. ALWAYS use this before scrolling or giving up. Use ACTUAL text that would appear on the page (e.g. "$" or "HK$" for prices, not "price").
        - scroll_to_text: Jump directly to a keyword match. Set "searchText". PREFERRED over scroll when you know the text to look for. Use after find_on_page confirms matches exist. Works even for hidden matches — the browser will scroll to and reveal the text.
        - scroll: Scroll the viewport. Set "scrollDirection" (DOWN/UP) and "scrollPercent" (10–100, default 100). Only use when scroll_to_text is not applicable (e.g. looking for visual content, or no keywords to search).
        - peek_full_page: Full-page overview screenshot. Last resort — use find_on_page first.

        Conclude:
        - answer_found: Set "answer". Only when openQuestions is empty. "answer" must be null for all other actions.
        - give_up: ABSOLUTE LAST RESORT. You must have done ALL of: (1) find_on_page with multiple keyword variations, (2) expanded any [collapsed] elements, (3) scrolled through or used scroll_to_text on the page. If find_on_page showed hidden matches, you MUST expand those hidden elements before giving up.

        === RESPONSE FORMAT ===
        - "thinking": REQUIRED. Your reasoning — what happened, what you see, what you'll do next. This becomes your memory across turns.
        - "finding": query-relevant data from the current screenshot. Null only if nothing relevant is visible.
        - "openQuestions": gaps remaining. Empty only when fully answered.
        - One action with its required fields.

        === LABELS ===
        - "labelNumber" must be a number from ELEMENT_LABELS. The valid range is shown there.
        - Numbers on the page (prices, phone numbers, addresses) are NOT labels.
        - Labels are small colored badges overlaid on interactive elements.

        === RULES ===
        - Study the screenshot fresh each turn — never carry stale data forward.
        - If a previous outcome says NO visible change → try a DIFFERENT element or approach.
        - For tables/grids, match row labels to column headers carefully.
        - Off-page clicks are automatically blocked. The outcome will say "Navigated OFF-PAGE". Do NOT re-click such elements. Focus on the current page instead.
        - NEVER click the same off-page element twice. After an off-page outcome, switch to exploring the current page.
        - Prefer find_on_page + scroll_to_text over blind scrolling. This is faster and more reliable.

        === VISUAL CAPTURE ===
        - If you see a relevant chart, diagram, or table image, set "captureRegions" with bounding box (0–1000 coordinates) and "relevance" description.
        - Ignore logos, icons, and navigation images.
    """.trimIndent()

    @Serializable
    private data class CaptureRegionResponse(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val relevance: String
    )

    @Serializable
    private data class NavigationResponse(
        val thinking: String? = null,
        val finding: String? = null,
        val openQuestions: List<String>? = null,
        val actionType: String,
        val labelNumber: Int? = null,
        val clickX: Int? = null,
        val clickY: Int? = null,
        val keywords: List<String>? = null,
        val searchText: String? = null,
        val occurrence: Int? = null,
        val scrollDirection: String? = null,
        val scrollPercent: Int? = null,
        val text: String? = null,
        val answer: String? = null,
        val reason: String? = null,
        val captureRegions: List<CaptureRegionResponse>? = null
    )

    override suspend fun generate(input: WebpageNavigationInput): WebpageNavigationOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val userPrompt = buildString {
            appendLine("PAGE: ${input.pageTitle} — ${input.pageUrl}")
            val desc = input.pageDescription?.take(200) ?: "none"
            appendLine("DESCRIPTION: $desc")
            appendLine("SCROLL_POSITION: ${input.scrollPercent}% (0% = top, 100% = bottom)")
            appendLine()
            appendLine("QUERY: ${input.query}")

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
                input.previousActions.forEachIndexed { idx, entry ->
                    appendLine("  ${idx + 1}. ${formatActionWithOutcome(entry)}")
                }
            }

            if (input.elementLabels.isNotEmpty()) {
                val maxLabel = input.elementLabels.maxOf { it.labelNumber }
                appendLine()
                appendLine("ELEMENT_LABELS (valid range: 0–$maxLabel):")
                input.elementLabels.forEach { el ->
                    val roleStr = el.role?.let { " ($it)" } ?: ""
                    val statesStr = if (el.states.isNotEmpty()) " [${el.states.joinToString(", ")}]" else ""
                    appendLine("  [${el.labelNumber}] ${el.tag}$roleStr$statesStr: ${el.text.take(60)}")
                }
            } else {
                appendLine()
                appendLine("ELEMENT_LABELS: None visible. Use scroll, find_on_page, or give_up.")
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

        if (action !is NavigationAction.AnswerFound && !response.answer.isNullOrBlank()) {
            logger.warn(
                "VLM speculatively filled answer for actionType={}, discarding: {}",
                response.actionType, response.answer.take(80)
            )
        }

        logger.debug(
            "Navigation: {} | finding={} | openQ={} | reason={}",
            action::class.simpleName,
            response.finding?.take(80),
            response.openQuestions?.size ?: 0,
            response.reason
        )

        val captureRegions = response.captureRegions?.map { r ->
            CaptureRegion(
                x1 = r.x1.coerceIn(0, 1000),
                y1 = r.y1.coerceIn(0, 1000),
                x2 = r.x2.coerceIn(0, 1000),
                y2 = r.y2.coerceIn(0, 1000),
                relevance = r.relevance
            )
        }?.filter { it.x2 > it.x1 && it.y2 > it.y1 } ?: emptyList()

        return WebpageNavigationOutput(
            action = action,
            finding = response.finding,
            thinking = response.thinking,
            openQuestions = response.openQuestions ?: emptyList(),
            captureRegions = captureRegions,
            tokenUsage = tokenUsage
        )
    }

    private fun parseAction(response: NavigationResponse): NavigationAction {
        return when (response.actionType) {
            "click" -> {
                val label = response.labelNumber?.takeIf { it >= 0 }
                    ?: extractLabelFromText(response.reason)
                if (label == null) {
                    logger.warn("VLM returned click without labelNumber, falling back to scroll")
                    scrollFallback()
                } else {
                    NavigationAction.Click(labelNumber = label, reason = response.reason ?: "")
                }
            }
            "click_at" -> {
                val x = response.clickX
                val y = response.clickY
                if (x == null || y == null) {
                    logger.warn("VLM returned click_at without coordinates (x={}, y={}), falling back to scroll", x, y)
                    scrollFallback()
                } else {
                    NavigationAction.ClickAt(
                        x = x.coerceIn(0, 1000),
                        y = y.coerceIn(0, 1000),
                        reason = response.reason ?: ""
                    )
                }
            }
            "scroll" -> NavigationAction.Scroll(
                scrollDirection = when (response.scrollDirection?.uppercase()) {
                    "UP" -> ScrollDirection.UP
                    else -> ScrollDirection.DOWN
                },
                scrollPercent = (response.scrollPercent ?: 100).coerceIn(10, 100),
                reason = response.reason ?: ""
            )
            "find_on_page" -> NavigationAction.FindOnPage(
                keywords = response.keywords ?: emptyList(),
                reason = response.reason ?: ""
            )
            "scroll_to_text" -> NavigationAction.ScrollToText(
                searchText = response.searchText ?: "",
                occurrence = 1,
                reason = response.reason ?: ""
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
            "peek_full_page" -> NavigationAction.PeekFullPage(
                reason = response.reason ?: ""
            )
            "type" -> {
                val label = response.labelNumber?.takeIf { it >= 0 }
                val text = response.text
                if (label == null || text.isNullOrBlank()) {
                    logger.warn("VLM returned type without labelNumber or text, falling back to scroll")
                    scrollFallback()
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

    private fun scrollFallback() = NavigationAction.Scroll(
        scrollDirection = ScrollDirection.DOWN,
        reason = "fallback"
    )

    private fun extractLabelFromText(text: String?): Int? {
        if (text == null) return null
        val match = Regex("""\blabel\s*(?:#\s*)?(\d+)\b""", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()?.also {
            logger.debug("Extracted labelNumber={} from reason text as fallback", it)
        }
    }

    private fun formatActionWithOutcome(entry: ActionWithOutcome): String = buildString {
        if (entry.thinking != null) append("Thinking: ${entry.thinking}\n     ")
        append("Action: ${formatActionDesc(entry.action)}")
        if (entry.outcome != null) append(" → ${entry.outcome}")
    }

    private fun formatActionDesc(action: NavigationAction): String = when (action) {
        is NavigationAction.Click -> {
            val target = action.elementDescription ?: "element"
            "Clicked $target — ${action.reason}"
        }
        is NavigationAction.ClickAt -> {
            val target = action.elementDescription ?: "unlabeled element"
            "Clicked at (${action.x},${action.y}) on $target — ${action.reason}"
        }
        is NavigationAction.Scroll -> "Scrolled ${action.scrollDirection.name.lowercase()} ${action.scrollPercent}%"
        is NavigationAction.FindOnPage -> "Ctrl+F: [${action.keywords.joinToString { "\"$it\"" }}]"
        is NavigationAction.ScrollToText -> "Scroll to \"${action.searchText}\""
        is NavigationAction.PeekFullPage -> "Peeked at full page overview"
        is NavigationAction.Type -> {
            val target = action.elementDescription ?: "element"
            "Typed '${action.text.take(30)}' into $target"
        }
        is NavigationAction.AnswerFound -> "Reported answer: ${action.answer.take(80)}"
        is NavigationAction.GiveUp -> "Gave up: ${action.reason}"
    }
}
