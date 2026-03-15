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

    private val actionSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "action" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("click", "scroll_page", "scroll_element", "find_on_page", "scroll_to_text", "peek_full_page", "type_text"))
                    .build(),
                "reason" to Schema.builder()
                    .type("STRING")
                    .description("Brief reason for the chosen action.")
                    .build(),
                "click" to Schema.builder()
                    .type("OBJECT")
                    .nullable(true)
                    .properties(mapOf(
                        "x" to Schema.builder().type("INTEGER").description("X coordinate (0-1000, where 0=left edge).").build(),
                        "y" to Schema.builder().type("INTEGER").description("Y coordinate (0-1000, where 0=top edge).").build()
                    ))
                    .required(listOf("x", "y"))
                    .build(),
                "scroll" to Schema.builder()
                    .type("OBJECT")
                    .nullable(true)
                    .properties(mapOf(
                        "direction" to Schema.builder().type("STRING").enum_(listOf("DOWN", "UP", "LEFT", "RIGHT")).build(),
                        "percent" to Schema.builder().type("INTEGER").description("Viewport percentage to scroll (10-100). Default 100.").build()
                    ))
                    .required(listOf("direction"))
                    .build(),
                "scrollAt" to Schema.builder()
                    .type("OBJECT")
                    .nullable(true)
                    .properties(mapOf(
                        "x" to Schema.builder().type("INTEGER").description("X coordinate of scrollable container (0-1000).").build(),
                        "y" to Schema.builder().type("INTEGER").description("Y coordinate of scrollable container (0-1000).").build(),
                        "direction" to Schema.builder().type("STRING").enum_(listOf("DOWN", "UP", "LEFT", "RIGHT")).build(),
                        "percent" to Schema.builder().type("INTEGER").description("Scroll amount as viewport percentage (10-100). Default 100.").build()
                    ))
                    .required(listOf("x", "y", "direction"))
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
                        "x" to Schema.builder().type("INTEGER").description("X coordinate of input field (0-1000).").build(),
                        "y" to Schema.builder().type("INTEGER").description("Y coordinate of input field (0-1000).").build(),
                        "text" to Schema.builder().type("STRING").description("Text to type.").build()
                    ))
                    .required(listOf("x", "y", "text"))
                    .build()
            )
        )
        .required(listOf("action", "reason"))
        .propertyOrdering(listOf("action", "reason", "click", "scroll", "scrollAt", "findOnPage", "scrollToText", "type"))
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
                "decision" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("explore", "answer_found", "give_up"))
                    .description("Top-level intent: explore the page further, report the answer, or give up.")
                    .build(),
                "reason" to Schema.builder()
                    .type("STRING")
                    .description("Brief reason for the chosen decision.")
                    .build(),
                "actions" to Schema.builder()
                    .type("ARRAY")
                    .items(actionSchema)
                    .description("Exploration actions to execute in order (decision=explore only). Eagerly include ALL actions that might yield information.")
                    .nullable(true)
                    .build(),
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Final answer synthesizing all findings (decision=answer_found only).")
                    .nullable(true)
                    .build()
            )
        )
        .required(listOf("observation", "findings", "openQuestions", "decision", "reason"))
        .propertyOrdering(listOf("observation", "findings", "openQuestions", "captureRegions", "decision", "reason", "actions", "answer"))
        .build()

    private val systemInstruction = """
        You are a webpage exploration agent. You examine a single web page to find specific information requested in QUERY.

        ## Input

        You receive on each turn:
        - A screenshot of the current viewport
        - ITERATION: current turn / max turns (budget awareness)
        - FINDINGS: accumulated facts discovered so far across all turns
        - OPEN QUESTIONS: what still needs to be found
        - TURNS: your previous observations, findings, actions, and their outcomes

        ## Instructions

        ### Strategy
        1. Check if the answer is already visible in the current viewport.
        2. Use find_on_page to assess whether the page contains the information. find_on_page searches with stemming and automatically scrolls to the best match.
        3. If find_on_page auto-scrolled, examine the new viewport for the answer. Use scroll_to_text to jump to a DIFFERENT match or occurrence.
        4. If find_on_page shows hidden matches → expand [collapsed] elements or use scroll_to_text (which reveals hidden content).
        5. Expand [collapsed] accordions/tabs/dropdowns — the answer is often hidden behind them.
        6. Only use scroll_page as a last resort when find_on_page returned no matches but the page may have visual-only content.
        7. Do NOT click off-page links as a first strategy. Explore the CURRENT page thoroughly first.
        8. If a table or panel is cut off horizontally, use scroll_element to scroll that specific container LEFT or RIGHT.

        ### Keyword tips
        Keywords must match ACTUAL TEXT on the page, not conceptual descriptions. The system handles stemming (e.g. "pricing" matches "prices").
        - If all keywords return 0 matches: try SHORTER prefixes (e.g., "consult" instead of "consultation") or NUMERIC values you expect to find.
        - Be exhaustive: include keywords covering different aspects, synonyms, and expected text variations. The search is fast — more keywords upfront give you a richer picture of the page in fewer iterations.
        - When find_on_page already found matches in a prior turn (see TURNS), do NOT re-search the same keywords. Act on the results you already have.

        ### Decision
        - **explore**: Continue investigating the page. You MUST provide a list of exploration actions (see below). Be eager and exhaustively include ALL actions you believe could yield relevant information.
        - **answer_found**: You have gathered enough information to answer the query. Provide the answer in the "answer" field. If running low on iterations with partial information, use answer_found with what you have — a partial answer is ALWAYS better than no answer. Prefix partial answers with "Based on available information:".
        - **give_up**: ABSOLUTE LAST RESORT. Only when FINDINGS is completely empty and you have exhausted ALL strategies. If you have ANY findings, use answer_found instead.

        ### Exploration actions (for decision=explore)
        **type_text**: Type into an input field. Specify (x, y) coordinates and the text. Highest priority because it can trigger search/filter results.
        **click**: Click an element at (x, y) coordinates (0-1000 scale, 0,0 = top-left, center of element). Include ALL clickable elements you want to investigate — buttons, tabs, accordions, links.
        **find_on_page**: Search page text for keywords with stemming. Returns match counts with context snippets. Auto-scrolls to best match. Hidden matches = content behind collapsed elements.
        **scroll_to_text**: Jump to specific text. Use AFTER find_on_page to navigate to a different match.
        **scroll_page**: Scroll the full viewport (UP/DOWN/LEFT/RIGHT). Use when scroll_to_text is not applicable.
        **scroll_element**: Scroll a specific container at (x, y) coordinates. Use for tables/panels cut off horizontally or vertically.
        **peek_full_page**: Full-page overview screenshot. Last resort — use find_on_page first.

        ### Rules
        - Study the screenshot fresh each turn — never carry stale data forward.
        - If a previous outcome says NO visible change → try a DIFFERENT element or approach.
        - For tables/grids, match row labels to column headers carefully. If columns are cut off, use scroll_element to scroll the table container horizontally.
        - Off-page clicks are automatically blocked. The outcome will say "Navigated OFF-PAGE". Do NOT re-click such elements.
        - NEVER click the same off-page element twice. After an off-page outcome, switch to exploring the current page.
        - Prefer find_on_page over blind scrolling.

        ### Visual capture
        If you see a relevant chart, diagram, or table image, include captureRegions with bounding box (0–1000 coordinates) and relevance description. Ignore logos, icons, and navigation images.

        ## Output

        Return JSON with this structure:
        {
          "observation": "What you see and your reasoning",
          "findings": ["Fact 1", "Fact 2"],
          "openQuestions": ["Unanswered question"],
          "decision": "explore",
          "reason": "Why this decision",
          "actions": [
            { "action": "click", "reason": "Click Learn More for Standard plan", "click": { "x": 300, "y": 400 } },
            { "action": "click", "reason": "Click Learn More for Comprehensive plan", "click": { "x": 700, "y": 400 } },
            { "action": "find_on_page", "reason": "Search for pricing keywords", "findOnPage": { "keywords": ["price", "cost", "$", "HK$"] } }
          ]
        }

        For answer_found: set decision="answer_found", provide reason, and set answer to your synthesized answer.
        For give_up: set decision="give_up" with reason. No actions needed.
        For explore: set decision="explore" with reason and list ALL exploration actions. Only populate each action's sub-object matching its action type. For peek_full_page, reason alone suffices.
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
    private data class ClickParams(val x: Int, val y: Int)

    @Serializable
    private data class ScrollParams(val direction: String? = null, val percent: Int? = null)

    @Serializable
    private data class ScrollAtParams(val x: Int? = null, val y: Int? = null, val direction: String? = null, val percent: Int? = null)

    @Serializable
    private data class FindOnPageParams(val keywords: List<String>? = null)

    @Serializable
    private data class ScrollToTextParams(val text: String? = null)

    @Serializable
    private data class TypeParams(val x: Int? = null, val y: Int? = null, val text: String? = null)

    @Serializable
    private data class ActionResponse(
        val action: String,
        val reason: String? = null,
        val click: ClickParams? = null,
        val scroll: ScrollParams? = null,
        val scrollAt: ScrollAtParams? = null,
        val findOnPage: FindOnPageParams? = null,
        val scrollToText: ScrollToTextParams? = null,
        val type: TypeParams? = null
    )

    @Serializable
    private data class NavigationResponse(
        val observation: String? = null,
        val findings: List<String>? = null,
        val openQuestions: List<String>? = null,
        val captureRegions: List<CaptureRegionResponse>? = null,
        val decision: String? = null,
        val reason: String? = null,
        val actions: List<ActionResponse>? = null,
        val answer: String? = null
    )

    override suspend fun generate(input: WebpageNavigationInput): WebpageNavigationOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val userPrompt = buildUserPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<NavigationResponse>(this@WebpageNavigationAgentGenAiImpl::class.simpleName!!) {
                val contentParts = listOf(
                    Part.fromText("VIEWPORT:"),
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

        val actions = when (response.decision) {
            "answer_found" -> {
                val answer = response.answer
                if (answer.isNullOrBlank()) {
                    logger.warn("VLM returned answer_found without answer text, treating as give_up")
                    listOf(NavigationAction.GiveUp(reason = "Model claimed answer_found but provided no answer"))
                } else {
                    listOf(NavigationAction.AnswerFound(answer = answer))
                }
            }
            "give_up" -> {
                listOf(NavigationAction.GiveUp(reason = response.reason ?: "No reason provided"))
            }
            else -> {
                (response.actions ?: emptyList()).map { parseAction(it) }
            }
        }

        logger.debug(
            "Navigation: decision={} actions=[{}] | findings={} | openQ={}",
            response.decision,
            actions.joinToString(", ") { it::class.simpleName ?: "?" },
            response.findings?.size ?: 0,
            response.openQuestions?.size ?: 0
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
            actions = actions,
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
        appendLine("ITERATION: ${input.currentIteration} / ${input.maxIterations}")
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

    }

    private fun parseAction(actionResp: ActionResponse): NavigationAction {
        val reason = actionResp.reason ?: ""
        return when (actionResp.action) {
            "click" -> {
                val params = actionResp.click
                if (params == null) {
                    logger.warn("VLM returned click without coordinates, falling back to scroll")
                    scrollFallback()
                } else {
                    NavigationAction.Click(
                        x = params.x.coerceIn(0, 1000),
                        y = params.y.coerceIn(0, 1000),
                        reason = reason
                    )
                }
            }
            "scroll_page" -> {
                val params = actionResp.scroll
                NavigationAction.Scroll(
                    scrollDirection = parseScrollDirection(params?.direction),
                    scrollPercent = (params?.percent ?: 100).coerceIn(10, 100),
                    reason = reason
                )
            }
            "scroll_element" -> {
                val params = actionResp.scrollAt
                if (params?.x == null || params.y == null || params.direction == null) {
                    logger.warn("VLM returned scroll_element with missing params, falling back to scroll")
                    scrollFallback()
                } else {
                    NavigationAction.ScrollAt(
                        x = params.x.coerceIn(0, 1000),
                        y = params.y.coerceIn(0, 1000),
                        scrollDirection = parseScrollDirection(params.direction),
                        scrollPercent = (params.percent ?: 100).coerceIn(10, 100),
                        reason = reason
                    )
                }
            }
            "find_on_page" -> NavigationAction.FindOnPage(
                keywords = actionResp.findOnPage?.keywords ?: emptyList(),
                reason = reason
            )
            "scroll_to_text" -> NavigationAction.ScrollToText(
                searchText = actionResp.scrollToText?.text ?: "",
                occurrence = 1,
                reason = reason
            )
            "peek_full_page" -> NavigationAction.PeekFullPage(reason = reason)
            "type_text" -> {
                val params = actionResp.type
                val x = params?.x
                val y = params?.y
                val text = params?.text
                if (x == null || y == null || text.isNullOrBlank()) {
                    logger.warn("VLM returned type_text without coordinates or text, falling back to scroll")
                    scrollFallback()
                } else {
                    NavigationAction.Type(
                        x = x.coerceIn(0, 1000),
                        y = y.coerceIn(0, 1000),
                        text = text,
                        reason = reason
                    )
                }
            }
            else -> {
                logger.warn("Unknown exploration action type: {}", actionResp.action)
                scrollFallback()
            }
        }
    }

    private fun parseScrollDirection(direction: String?): ScrollDirection = when (direction?.uppercase()) {
        "UP" -> ScrollDirection.UP
        "LEFT" -> ScrollDirection.LEFT
        "RIGHT" -> ScrollDirection.RIGHT
        else -> ScrollDirection.DOWN
    }

    private fun scrollFallback() = NavigationAction.Scroll(
        scrollDirection = ScrollDirection.DOWN,
        reason = "fallback"
    )

    private fun formatTurnEntry(turnNumber: Int, entry: ActionWithOutcome): String = buildString {
        appendLine("  [$turnNumber] observation: ${entry.observation ?: "(none)"}")
        if (entry.findings.isNotEmpty()) {
            appendLine("      findings: [${entry.findings.joinToString(", ") { "\"$it\"" }}]")
        }
        append("      action: ${formatActionDesc(entry.action)}")
        if (entry.outcome != null) {
            appendLine()
            append("      outcome: ${entry.outcome}")
        }
    }

    private fun formatActionDesc(action: NavigationAction): String = when (action) {
        is NavigationAction.Click -> {
            "click ${action.reason.take(60).ifEmpty { "(${action.x},${action.y})" }}"
        }
        is NavigationAction.Scroll -> "scroll_page ${action.scrollDirection.name.lowercase()} ${action.scrollPercent}%"
        is NavigationAction.ScrollAt -> "scroll_element (${action.x},${action.y}) ${action.scrollDirection.name.lowercase()} ${action.scrollPercent}%"
        is NavigationAction.FindOnPage -> "find_on_page [${action.keywords.joinToString(", ") { "\"$it\"" }}]"
        is NavigationAction.ScrollToText -> "scroll_to_text \"${action.searchText}\""
        is NavigationAction.PeekFullPage -> "peek_full_page"
        is NavigationAction.Type -> {
            "type_text '${action.text.take(30)}' into ${action.reason.take(60).ifEmpty { "(${action.x},${action.y})" }}"
        }
        is NavigationAction.AnswerFound -> "answer_found: ${action.answer.take(80)}"
        is NavigationAction.GiveUp -> "give_up: ${action.reason}"
    }
}
