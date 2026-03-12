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
                    .description("For find_on_page: keywords to count on the page. The system returns how many times each keyword appears in visible text.")
                    .build(),
                "searchText" to Schema.builder()
                    .type("STRING")
                    .description("For scroll_to_text: the keyword to scroll to.")
                    .build(),
                "occurrence" to Schema.builder()
                    .type("INTEGER")
                    .description("For scroll_to_text: which match to scroll to (1 = first, 2 = second, etc.). Default 1.")
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
        .required(listOf("actionType", "openQuestions"))
        .propertyOrdering(listOf(
            "actionType", "finding", "openQuestions", "reason",
            "labelNumber", "clickX", "clickY", "keywords", "searchText", "occurrence",
            "scrollDirection", "scrollPercent", "text",
            "captureRegions", "answer"
        ))
        .build()

    private val systemInstruction = """
        You are a webpage exploration agent. You examine a single page to find information.

        You see the CURRENT VIEWPORT as an annotated screenshot. Numbered badges match ELEMENT_LABELS.

        === HIDDEN CONTENT — READ THIS FIRST ===
        Web pages hide content behind collapsible sections (accordions, "Learn more", "Show more", FAQ items, expandable rows).
        ELEMENT_LABELS marks these as [collapsed]. You MUST click [collapsed] elements to reveal what's inside.
        NEVER conclude information is absent without first expanding [collapsed] elements that could be relevant.

        === EACH TURN ===
        1. OBSERVE — What content is visible? Check ELEMENT_LABELS for any [collapsed] elements. Check SCROLL_POSITION.
        2. RECORD — Set "finding" with data extracted from the current screenshot. Do this BEFORE acting — the viewport changes after your action.
        3. ACT — Pick ONE action. Priority order:
           a. Click any [collapsed] element that might contain relevant content.
           b. find_on_page to Ctrl+F search for keywords — you get match counts showing how many times each keyword appears on the page. Use this to assess relevance before scrolling.
           c. scroll_to_text to jump to a specific match (by keyword and occurrence number).
           d. scroll to browse content by direction and percentage.
           e. Click links that look relevant to the query — if they lead off-page, the URL is recorded for later investigation.
           f. peek_full_page as a last resort to see the full page layout.
           g. answer_found when all openQuestions are answered.
           h. give_up — LAST RESORT. Only after you have used find_on_page, expanded relevant [collapsed] elements, AND scrolled through the page.

        === ACTIONS ===
        Interact:
        - click: Click a labeled element. Set "labelNumber".
        - click_at: Click an unlabeled element by coordinates. Set "clickX"/"clickY" (0–1000 scale).
        - type: Type into a labeled input. Set "labelNumber" and "text".

        Explore:
        - find_on_page: Ctrl+F search. Set "keywords" (list of terms). Returns match counts for each keyword in visible page text. Use this FIRST to assess whether the page contains what you need, then scroll_to_text to navigate to matches.
        - scroll_to_text: Jump to a keyword match. Set "searchText" and "occurrence" (1 = first match, 2 = second, etc.). Use after find_on_page tells you matches exist.
        - scroll: Scroll the viewport. Set "scrollDirection" (DOWN/UP) and "scrollPercent" (10–100, default 100).
        - peek_full_page: Full-page overview screenshot. Last resort — use find_on_page first.

        Conclude:
        - answer_found: Set "answer". Only when openQuestions is empty. "answer" must be null for all other actions.
        - give_up: Only after using find_on_page, expanding relevant [collapsed] elements, AND scrolling through the page.

        === RESPONSE FORMAT ===
        - "finding": data from the current screenshot. Null only if nothing relevant is visible.
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
        - Off-page clicks are automatically blocked. The outcome will say "Navigated OFF-PAGE". Do NOT re-click such elements.

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
                occurrence = (response.occurrence ?: 1).coerceAtLeast(1),
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

    private fun formatActionWithOutcome(entry: ActionWithOutcome): String {
        val desc = formatActionDesc(entry.action)
        return if (entry.outcome != null) "$desc → ${entry.outcome}" else desc
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
        is NavigationAction.ScrollToText -> "Scroll to \"${action.searchText}\" (occurrence ${action.occurrence})"
        is NavigationAction.PeekFullPage -> "Peeked at full page overview"
        is NavigationAction.Type -> {
            val target = action.elementDescription ?: "element"
            "Typed '${action.text.take(30)}' into $target"
        }
        is NavigationAction.AnswerFound -> "Reported answer: ${action.answer.take(80)}"
        is NavigationAction.GiveUp -> "Gave up: ${action.reason}"
    }
}
