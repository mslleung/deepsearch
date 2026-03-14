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

    private val captureRegionSchema: Schema = Schema.builder()
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

    private val decisionSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "action" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("click", "click_at", "scroll", "find_on_page", "scroll_to_text", "peek_full_page", "type", "answer_found", "give_up"))
                    .build(),
                "reason" to Schema.builder()
                    .type("STRING")
                    .description("Brief reason for the chosen action.")
                    .build(),
                "click" to Schema.builder()
                    .type("OBJECT")
                    .nullable(true)
                    .properties(mapOf(
                        "label" to Schema.builder().type("INTEGER").description("Element label number from ELEMENTS list.").build()
                    ))
                    .required(listOf("label"))
                    .build(),
                "clickAt" to Schema.builder()
                    .type("OBJECT")
                    .nullable(true)
                    .properties(mapOf(
                        "x" to Schema.builder().type("INTEGER").description("X coordinate (0-1000).").build(),
                        "y" to Schema.builder().type("INTEGER").description("Y coordinate (0-1000).").build()
                    ))
                    .required(listOf("x", "y"))
                    .build(),
                "scroll" to Schema.builder()
                    .type("OBJECT")
                    .nullable(true)
                    .properties(mapOf(
                        "direction" to Schema.builder().type("STRING").enum_(listOf("DOWN", "UP")).build(),
                        "percent" to Schema.builder().type("INTEGER").description("Viewport percentage to scroll (10-100). Default 100.").build()
                    ))
                    .required(listOf("direction"))
                    .build(),
                "findOnPage" to Schema.builder()
                    .type("OBJECT")
                    .nullable(true)
                    .properties(mapOf(
                        "keywords" to Schema.builder().type("ARRAY").items(Schema.builder().type("STRING").build())
                            .description("Keywords to search for. Use actual page text, not abstract concepts.").build()
                    ))
                    .required(listOf("keywords"))
                    .build(),
                "scrollToText" to Schema.builder()
                    .type("OBJECT")
                    .nullable(true)
                    .properties(mapOf(
                        "text" to Schema.builder().type("STRING").description("The text to scroll to on the page.").build()
                    ))
                    .required(listOf("text"))
                    .build(),
                "type" to Schema.builder()
                    .type("OBJECT")
                    .nullable(true)
                    .properties(mapOf(
                        "label" to Schema.builder().type("INTEGER").description("Element label number for the input field.").build(),
                        "text" to Schema.builder().type("STRING").description("Text to type.").build()
                    ))
                    .required(listOf("label", "text"))
                    .build(),
                "answerFound" to Schema.builder()
                    .type("OBJECT")
                    .nullable(true)
                    .properties(mapOf(
                        "answer" to Schema.builder().type("STRING").description("Final answer synthesizing all findings.").build()
                    ))
                    .required(listOf("answer"))
                    .build()
            )
        )
        .required(listOf("action", "reason"))
        .propertyOrdering(listOf("action", "reason", "click", "clickAt", "scroll", "findOnPage", "scrollToText", "type", "answerFound"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "observation" to Schema.builder()
                    .type("STRING")
                    .description("What you see on the current screen, what happened from your last action, and your plan. This is your memory across turns.")
                    .build(),
                "findings" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("Query-relevant facts extracted from the current viewport. Each string is one discrete fact. Empty array if nothing relevant is visible.")
                    .build(),
                "openQuestions" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("Questions still needing answers. Empty when fully resolved.")
                    .build(),
                "captureRegions" to Schema.builder()
                    .type("ARRAY")
                    .items(captureRegionSchema)
                    .description("Bounding boxes of visual regions (charts, diagrams, tables) worth capturing. Use 0-1000 coordinates. Empty if nothing visual to capture.")
                    .nullable(true)
                    .build(),
                "decision" to decisionSchema
            )
        )
        .required(listOf("observation", "findings", "openQuestions", "decision"))
        .propertyOrdering(listOf("observation", "findings", "openQuestions", "captureRegions", "decision"))
        .build()

    private val systemInstruction = """
        You are a webpage exploration agent. You examine a single web page to find specific information requested in QUERY.

        ## Input

        You receive on each turn:
        - An annotated screenshot of the current viewport (numbered badges = interactive elements)
        - FINDINGS: accumulated facts discovered so far across all turns
        - OPEN QUESTIONS: what still needs to be found
        - TURNS: your previous observations, findings, decisions, and their outcomes
        - ELEMENTS: interactive element labels (numbered badges on the screenshot)

        ## Instructions

        ### Strategy (mandatory priority order)
        1. Check if the answer is already visible in the current viewport.
        2. ALWAYS use find_on_page FIRST (before scrolling or clicking) to assess whether the page contains the information. This is your most important action. find_on_page automatically scrolls to the best match, so you'll see new content in the next turn.
        3. If find_on_page auto-scrolled, examine the new viewport for the answer. Use scroll_to_text only to jump to a DIFFERENT match or occurrence.
        4. If find_on_page shows hidden matches → expand [collapsed] elements or use scroll_to_text (which reveals hidden content).
        5. Expand [collapsed] accordions/tabs/dropdowns — the answer is often hidden behind them.
        6. Only use scroll as a last resort when find_on_page returned no matches but the page may have visual-only content.
        7. Do NOT click off-page links as a first strategy. Explore the CURRENT page thoroughly first.

        ### Keyword tips
        Keywords don't need to be exact — the system handles stemming (e.g. "pricing" matches "prices", "running" matches "runs"). Choose keywords that are likely to appear on the page:
        - For prices: include currency symbols like "$", "HK$", "£", "€", or specific amounts like "5,900".
        - For features: search the feature name, e.g. "Stress Test", "role-based".
        - If first keywords return 0 matches, try synonyms or shorter fragments.
        - Always include at least one highly specific keyword AND one broader keyword.

        ### Actions

        Interact:
        - click: Click a labeled element. Elements marked [collapsed] hide content — click them to reveal what's inside before concluding info is absent.
        - click_at: Click an unlabeled element by coordinates (0–1000 scale).
        - type: Type into a labeled input.

        Explore:
        - find_on_page: Search page text for keywords with stemming (morphological matching). Returns match counts per keyword with context snippets. Automatically scrolls to the best visible match, so you see new content next turn. Hidden matches (e.g. "keyword: 0 (2 hidden)") mean content exists behind collapsed/hidden elements — expand them or use scroll_to_text. ALWAYS use this before scrolling or giving up.
        - scroll_to_text: Jump directly to specific text. Use AFTER find_on_page when you want to navigate to a DIFFERENT match than the auto-scrolled one, or to a specific occurrence.
        - scroll: Scroll the viewport. Only use when scroll_to_text is not applicable.
        - peek_full_page: Full-page overview screenshot. Last resort — use find_on_page first.

        Conclude:
        - answer_found: Only when openQuestions is empty. Set the answer in decision.answerFound.
        - give_up: ABSOLUTE LAST RESORT. You must have done ALL of: (1) find_on_page with multiple keyword variations, (2) expanded any [collapsed] elements, (3) scrolled through or used scroll_to_text. If find_on_page showed hidden matches, you MUST expand those hidden elements before giving up.

        ### Rules
        - Study the screenshot fresh each turn — never carry stale data forward.
        - If a previous outcome says NO visible change → try a DIFFERENT element or approach.
        - For tables/grids, match row labels to column headers carefully.
        - Off-page clicks are automatically blocked. The outcome will say "Navigated OFF-PAGE". Do NOT re-click such elements.
        - NEVER click the same off-page element twice. After an off-page outcome, switch to exploring the current page.
        - Prefer find_on_page over blind scrolling. After find_on_page auto-scrolls, use scroll_to_text only for different matches.
        - Label numbers come from ELEMENTS. Do not confuse page content (prices, phone numbers) with label badges.

        ### Visual capture
        If you see a relevant chart, diagram, or table image, include captureRegions with bounding box (0–1000 coordinates) and relevance description. Ignore logos, icons, and navigation images.

        ## Output

        Return JSON with this structure:
        {
          "observation": "What you see on the current screen and your reasoning about what to do next",
          "findings": ["Fact 1 extracted from viewport", "Fact 2"],
          "openQuestions": ["Question still needing an answer"],
          "decision": {
            "action": "click",
            "reason": "Why this action",
            "click": { "label": 41 }
          }
        }

        Only populate the decision sub-object that matches the action. For give_up and peek_full_page, reason alone suffices (no sub-object needed).
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
    private data class ClickParams(val label: Int)

    @Serializable
    private data class ClickAtParams(val x: Int, val y: Int)

    @Serializable
    private data class ScrollParams(val direction: String? = null, val percent: Int? = null)

    @Serializable
    private data class FindOnPageParams(val keywords: List<String>? = null)

    @Serializable
    private data class ScrollToTextParams(val text: String? = null)

    @Serializable
    private data class TypeParams(val label: Int? = null, val text: String? = null)

    @Serializable
    private data class AnswerFoundParams(val answer: String? = null)

    @Serializable
    private data class DecisionResponse(
        val action: String,
        val reason: String? = null,
        val click: ClickParams? = null,
        val clickAt: ClickAtParams? = null,
        val scroll: ScrollParams? = null,
        val findOnPage: FindOnPageParams? = null,
        val scrollToText: ScrollToTextParams? = null,
        val type: TypeParams? = null,
        val answerFound: AnswerFoundParams? = null
    )

    @Serializable
    private data class NavigationResponse(
        val observation: String? = null,
        val findings: List<String>? = null,
        val openQuestions: List<String>? = null,
        val captureRegions: List<CaptureRegionResponse>? = null,
        val decision: DecisionResponse
    )

    override suspend fun generate(input: WebpageNavigationInput): WebpageNavigationOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val userPrompt = buildUserPrompt(input)

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

        val decision = response.decision
        val action = parseAction(decision)

        if (action !is NavigationAction.AnswerFound && decision.answerFound?.answer != null) {
            logger.warn(
                "VLM speculatively filled answerFound for action={}, discarding: {}",
                decision.action, decision.answerFound.answer.take(80)
            )
        }

        logger.debug(
            "Navigation: {} | findings={} | openQ={} | reason={}",
            action::class.simpleName,
            response.findings?.size ?: 0,
            response.openQuestions?.size ?: 0,
            decision.reason
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
            findings = response.findings ?: emptyList(),
            observation = response.observation,
            openQuestions = response.openQuestions ?: emptyList(),
            captureRegions = captureRegions,
            tokenUsage = tokenUsage
        )
    }

    private fun buildUserPrompt(input: WebpageNavigationInput): String = buildString {
        appendLine("PAGE: ${input.pageTitle} — ${input.pageUrl}")
        appendLine("SCROLL: ${input.scrollPercent}%")
        appendLine()
        appendLine("QUERY: ${input.query}")

        if (input.accumulatedFindings.isNotEmpty()) {
            appendLine()
            appendLine("FINDINGS (what we know so far):")
            input.accumulatedFindings.forEachIndexed { idx, f ->
                appendLine("  ${idx + 1}. $f")
            }
        }

        if (input.openQuestions.isNotEmpty()) {
            appendLine()
            appendLine("OPEN QUESTIONS (must resolve before answer_found):")
            input.openQuestions.forEachIndexed { idx, q ->
                appendLine("  ${idx + 1}. $q")
            }
        } else if (input.answeredQuestions.isNotEmpty()) {
            appendLine()
            appendLine("OPEN QUESTIONS: None — all questions resolved. You may use answer_found.")
        }

        if (input.previousActions.isNotEmpty()) {
            appendLine()
            appendLine("TURNS:")
            input.previousActions.forEachIndexed { idx, entry ->
                appendLine(formatTurnEntry(idx + 1, entry))
            }
        }

        if (input.elementLabels.isNotEmpty()) {
            val maxLabel = input.elementLabels.maxOf { it.labelNumber }
            appendLine()
            appendLine("ELEMENTS (label range 0–$maxLabel):")
            input.elementLabels.forEach { el ->
                val roleStr = el.role?.let { " ($it)" } ?: ""
                val statesStr = if (el.states.isNotEmpty()) " [${el.states.joinToString(", ")}]" else ""
                appendLine("  [${el.labelNumber}] ${el.tag}$roleStr$statesStr: ${el.text.take(60)}")
            }
        } else {
            appendLine()
            appendLine("ELEMENTS: None visible. Use scroll, find_on_page, or give_up.")
        }
    }

    private fun parseAction(decision: DecisionResponse): NavigationAction {
        val reason = decision.reason ?: ""
        return when (decision.action) {
            "click" -> {
                val label = decision.click?.label?.takeIf { it >= 0 }
                    ?: extractLabelFromText(reason)
                if (label == null) {
                    logger.warn("VLM returned click without label, falling back to scroll")
                    scrollFallback()
                } else {
                    NavigationAction.Click(labelNumber = label, reason = reason)
                }
            }
            "click_at" -> {
                val params = decision.clickAt
                if (params == null) {
                    logger.warn("VLM returned click_at without clickAt params, falling back to scroll")
                    scrollFallback()
                } else {
                    NavigationAction.ClickAt(
                        x = params.x.coerceIn(0, 1000),
                        y = params.y.coerceIn(0, 1000),
                        reason = reason
                    )
                }
            }
            "scroll" -> {
                val params = decision.scroll
                NavigationAction.Scroll(
                    scrollDirection = when (params?.direction?.uppercase()) {
                        "UP" -> ScrollDirection.UP
                        else -> ScrollDirection.DOWN
                    },
                    scrollPercent = (params?.percent ?: 100).coerceIn(10, 100),
                    reason = reason
                )
            }
            "find_on_page" -> NavigationAction.FindOnPage(
                keywords = decision.findOnPage?.keywords ?: emptyList(),
                reason = reason
            )
            "scroll_to_text" -> NavigationAction.ScrollToText(
                searchText = decision.scrollToText?.text ?: "",
                occurrence = 1,
                reason = reason
            )
            "answer_found" -> {
                val answer = decision.answerFound?.answer
                if (answer.isNullOrBlank()) {
                    logger.warn("VLM returned answer_found without answer text, treating as give_up")
                    NavigationAction.GiveUp(reason = "Model claimed answer_found but provided no answer")
                } else {
                    NavigationAction.AnswerFound(answer = answer)
                }
            }
            "peek_full_page" -> NavigationAction.PeekFullPage(reason = reason)
            "type" -> {
                val params = decision.type
                val label = params?.label?.takeIf { it >= 0 }
                val text = params?.text
                if (label == null || text.isNullOrBlank()) {
                    logger.warn("VLM returned type without label or text, falling back to scroll")
                    scrollFallback()
                } else {
                    NavigationAction.Type(labelNumber = label, text = text, reason = reason)
                }
            }
            "give_up" -> NavigationAction.GiveUp(reason = reason.ifEmpty { "No reason provided" })
            else -> NavigationAction.GiveUp(reason = "Unknown action type: ${decision.action}")
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
            logger.debug("Extracted label={} from reason text as fallback", it)
        }
    }

    private fun formatTurnEntry(turnNumber: Int, entry: ActionWithOutcome): String = buildString {
        appendLine("  [$turnNumber] observation: ${entry.observation ?: "(none)"}")
        if (entry.findings.isNotEmpty()) {
            appendLine("      findings: [${entry.findings.joinToString(", ") { "\"$it\"" }}]")
        }
        append("      decision: ${formatDecisionDesc(entry.action)}")
        if (entry.outcome != null) {
            appendLine()
            append("      outcome: ${entry.outcome}")
        }
    }

    private fun formatDecisionDesc(action: NavigationAction): String = when (action) {
        is NavigationAction.Click -> {
            val target = action.elementDescription ?: "label ${action.labelNumber}"
            "click $target"
        }
        is NavigationAction.ClickAt -> {
            val target = action.elementDescription ?: "unlabeled element"
            "click_at (${action.x},${action.y}) on $target"
        }
        is NavigationAction.Scroll -> "scroll ${action.scrollDirection.name.lowercase()} ${action.scrollPercent}%"
        is NavigationAction.FindOnPage -> "find_on_page [${action.keywords.joinToString(", ") { "\"$it\"" }}]"
        is NavigationAction.ScrollToText -> "scroll_to_text \"${action.searchText}\""
        is NavigationAction.PeekFullPage -> "peek_full_page"
        is NavigationAction.Type -> {
            val target = action.elementDescription ?: "label ${action.labelNumber}"
            "type '${action.text.take(30)}' into $target"
        }
        is NavigationAction.AnswerFound -> "answer_found: ${action.answer.take(80)}"
        is NavigationAction.GiveUp -> "give_up: ${action.reason}"
    }
}
