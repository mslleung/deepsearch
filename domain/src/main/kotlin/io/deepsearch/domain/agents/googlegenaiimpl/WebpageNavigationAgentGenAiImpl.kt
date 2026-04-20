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
import io.deepsearch.domain.agents.SearchKeywordsResult
import io.deepsearch.domain.agents.TrackedQuestion
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
                "relevance" to Schema.builder().type("STRING").description("Why this visual region is relevant to the query.").build(),
                "containsTable" to Schema.builder().type("BOOLEAN")
                    .description("True if this region contains tabular data (comparison grid, pricing table, feature matrix, data table, or an image of a table). False for plain text, paragraphs, or single values.")
                    .build()
            )
        )
        .required(listOf("x1", "y1", "x2", "y2", "relevance", "containsTable"))
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
                        "element_label" to Schema.builder().type("INTEGER")
                            .description("Number shown on the annotated screenshot for the element to click. Preferred over box_2d.")
                            .nullable(true)
                            .build(),
                        "box_2d" to Schema.builder().type("ARRAY")
                            .description("Fallback: bounding box [ymin, xmin, ymax, xmax] in 0-1000 scale. Only use when no element_label matches the target.")
                            .items(Schema.builder().type("INTEGER").build())
                            .nullable(true)
                            .build(),
                        "label" to Schema.builder().type("STRING")
                            .description("Brief description of the element being clicked.")
                            .build()
                    ))
                    .required(listOf("label"))
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
                        "text" to Schema.builder().type("STRING").description("The text to search for outside the current viewport.").build(),
                        "direction" to Schema.builder().type("STRING").enum_(listOf("DOWN", "UP")).description("Direction to search from the current viewport. Default DOWN.").build()
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

    private val questionStateSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "question" to Schema.builder().type("STRING").description("The question text.").build(),
                "status" to Schema.builder().type("STRING").enum_(listOf("open", "resolved"))
                    .description("Whether this question is still open or has been resolved by findings.").build(),
                "findings" to Schema.builder().type("ARRAY").items(Schema.builder().type("STRING").build())
                    .description("Facts discovered that help answer this question. Empty if no findings yet.").build()
            )
        )
        .required(listOf("question", "status", "findings"))
        .propertyOrdering(listOf("question", "status", "findings"))
        .build()

    // ── Combined output schema: visual analysis fields first, then query planning ──

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "pageState" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description(
                        "Current state of ALL tracked dynamic UI elements. " +
                        "For elements VISIBLE in the screenshot: report their state based on what you SEE " +
                        "(highlighted, selected, expanded, etc.). " +
                        "For PREVIOUS entries no longer visible in the viewport: carry them forward unchanged " +
                        "— do NOT drop them. " +
                        "Only UPDATE or REMOVE a previous entry if you can see that the element's state " +
                        "has actually changed. " +
                        "Each entry: a short factual statement like 'Active tab: Cardiovascular Risk Package'."
                    )
                    .build(),
                "observation" to Schema.builder()
                    .type("STRING")
                    .description("Describe the page layout, visible sections, and any changes from the last action.")
                    .build(),
                "visibleContent" to Schema.builder()
                    .type("STRING")
                    .description(
                        "Structured extraction of ALL visible text content in the current viewport, " +
                        "formatted as markdown. " +
                        "Use markdown tables for any tabular data (preserve rows, columns, and headers). " +
                        "Use headings, lists, and emphasis to reflect the page structure. " +
                        "Preserve the original text exactly — do NOT paraphrase or summarize."
                    )
                    .build(),
                "questionsState" to Schema.builder()
                    .type("ARRAY")
                    .items(questionStateSchema)
                    .description("The COMPLETE updated state of ALL questions. Include every question (open and resolved) with ALL their findings. On the first turn, decompose the QUERY into sub-questions. On later turns, carry forward ALL previous questions and findings, adding new findings or marking questions resolved.")
                    .build(),
                "generalFindings" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("Facts not tied to any specific question (e.g. page context, site structure). Carry forward all previous general findings and add new ones.")
                    .build(),
                "captureRegions" to Schema.builder()
                    .type("ARRAY")
                    .items(captureRegionSchema)
                    .description("Bounding boxes of visual regions (charts, diagrams, tables) worth capturing. Use 0-1000 coordinates. Empty if nothing visual to capture.")
                    .nullable(true)
                    .build(),
                "decision" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("continue_exploring", "exploration_finished"))
                    .description("Top-level intent: continue exploring the page, or finish exploration.")
                    .build(),
                "reason" to Schema.builder()
                    .type("STRING")
                    .description("Brief reason for the chosen decision.")
                    .build(),
                "actions" to Schema.builder()
                    .type("ARRAY")
                    .items(actionSchema)
                    .description("Exploration actions to execute in order (decision=continue_exploring only). Eagerly include ALL actions that might yield information.")
                    .nullable(true)
                    .build(),
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("REQUIRED when decision=exploration_finished. Comprehensive answer synthesized from ALL findings. Include every relevant detail. Only null when decision is continue_exploring.")
                    .nullable(true)
                    .build()
            )
        )
        .required(listOf("pageState", "observation", "visibleContent", "questionsState", "generalFindings", "decision", "reason"))
        .propertyOrdering(listOf("pageState", "observation", "visibleContent", "questionsState", "generalFindings", "captureRegions", "decision", "reason", "actions", "answer"))
        .build()

    private val systemInstruction = """
        You are a webpage exploration agent. You work in two stages within each response.

        ## STAGE 1 — VISUAL ANALYSIS
        Complete this stage based ONLY on what you SEE in the screenshot — before considering the query.
        - pageState: Report ALL dynamic UI states (which tab is highlighted, which sections are expanded/collapsed, which toggles are on/off). Carry forward previous entries for off-screen elements.
        - observation: Describe the page layout and any changes from the last action.
        - visibleContent: Extract ALL visible text as markdown — use tables for tabular data, headings for sections, lists for bullet points. Preserve original text exactly.
        - If text is in another language, keep it in the original language.
        CRITICAL: Do NOT let the query influence your visual analysis. If the query mentions "X", do not assume X is visible — look at the screenshot and report what is ACTUALLY there.

        ## STAGE 2 — QUERY PLANNING
        Now read the query and use your Stage 1 analysis to decide what to do.
        1. Trust your Stage 1 analysis. If you reported "Active tab: Y" in pageState, that IS the active tab — even if you expected a different tab based on the query or your previous click. If the state doesn't match what you expected, your last click likely targeted the wrong element — try a different one.
        2. Extract any relevant findings from visibleContent. Do not omit data. Do not invent or modify facts.
        3. Explore the page by issuing actions: click, scroll, search.
        4. If findings are near viewport edges, scroll to see if content continues.
        5. If a click led to navigation, it would be recorded separately, just continue exploring.
        6. When find_on_page or pre-scan has reported that keywords exist on the current page, use scroll_to_text to navigate directly to those keywords. Do NOT click through navigation menus to reach content already confirmed to be on this page.
        7. Before calling exploration_finished: VERIFY that every data point in your answer appears verbatim in your visibleContent extractions across turns. If any data point is NOT in your extractions, continue exploring to find it — do NOT guess or fabricate.

        ## Efficiency
        - ALWAYS use scroll_to_text when you know specific text exists on the page (from find_on_page results or visible text). This is your PRIMARY navigation tool.
        - NEVER use scroll_page when you have specific text to navigate to. scroll_page is ONLY for blind exploration of unknown content.
        - Do NOT click through navigation menus to reach sections already on the current page — use scroll_to_text to jump there directly.
        - Pack multiple relevant keywords into a SINGLE find_on_page call.
        - When find_on_page reports hidden matches, scroll_to_text to a nearby visible anchor, then click to expand.
        - For tables extending beyond the viewport: use scroll_to_text with specific cell values, column headers, or row labels to see the complete table before answering.
        - When match counts show data-bearing keywords (e.g., currency symbols like HK$, specific numbers, technical terms) exist on the page but you haven't seen the actual values yet, scroll_to_text to those keywords BEFORE finishing.
        - Do NOT repeatedly scroll through the same page regions.
        - Use peek_full_page as a last resort when unsure about page structure.

        ## Decision & Actions
        **continue_exploring**: Provide ALL exploration actions you think will yield information.
        - **type_text**: Type into input field at (x,y). Highest priority.
        - **click**: Click an interactive element by element_label (preferred) or box_2d [ymin, xmin, ymax, xmax] 0-1000 scale. Every click MUST include element_label or box_2d coordinates.
        - **find_on_page**: Search keywords (with stemming). Batch ALL relevant keywords in a single call.
        - **scroll_to_text**: Scroll UP/DOWN to the next occurrence of text outside viewport. Use this whenever you know what text to look for.
        - **scroll_page**: Scroll viewport UP/DOWN/LEFT/RIGHT. Only when you have no specific text target. Try 100% unless it would cut text.
        - **scroll_element**: Scroll container at (x,y).
        - **peek_full_page**: Full-page overview. Last resort.

        **exploration_finished**: Stop exploring. You MUST provide a comprehensive answer in the "answer" field, synthesized from ALL findings.
        - Your answer MUST contain ONLY data you have directly read from the page in your visibleContent extractions. NEVER fabricate, estimate, or infer values you have not seen.
        - If you haven't found sufficient data, continue exploring rather than guessing.
        - Never leave answer empty when you have findings.
    """.trimIndent()

    @Serializable
    private data class CaptureRegionResponse(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val relevance: String,
        val containsTable: Boolean = false
    )

    @Serializable
    private data class ClickParams(val element_label: Int? = null, val box_2d: List<Int>? = null, val label: String? = null)

    @Serializable
    private data class ScrollParams(val direction: String? = null, val percent: Int? = null)

    @Serializable
    private data class ScrollAtParams(val x: Int? = null, val y: Int? = null, val direction: String? = null, val percent: Int? = null)

    @Serializable
    private data class FindOnPageParams(val keywords: List<String>? = null)

    @Serializable
    private data class ScrollToTextParams(val text: String? = null, val direction: String? = null)

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
    private data class QuestionStateResponse(
        val question: String,
        val status: String? = null,
        val findings: List<String>? = null
    )

    @Serializable
    private data class NavigationResponse(
        val pageState: List<String>? = null,
        val observation: String? = null,
        val visibleContent: String? = null,
        val questionsState: List<QuestionStateResponse>? = null,
        val generalFindings: List<String>? = null,
        val captureRegions: List<CaptureRegionResponse>? = null,
        val decision: String? = null,
        val reason: String? = null,
        val actions: List<ActionResponse>? = null,
        val answer: String? = null
    )

    override suspend fun generate(input: WebpageNavigationInput): WebpageNavigationOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val prompt = buildPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<NavigationResponse>(this@WebpageNavigationAgentGenAiImpl::class.simpleName!! + ".navigate") {
                val contentParts = listOf(
                    Part.fromText("VIEWPORT:"),
                    Part.fromBytes(input.screenshot.bytes, input.screenshot.mimeType.value),
                    Part.fromText(prompt)
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

        val pageState = response.pageState ?: emptyList()
        logger.debug("Navigation: pageState={}", pageState)

        val actions = when (response.decision) {
            "exploration_finished" -> {
                listOf(NavigationAction.ExplorationFinished(
                    answer = response.answer?.takeIf { it.isNotBlank() }
                ))
            }
            else -> {
                (response.actions ?: emptyList()).mapNotNull { action ->
                    try {
                        toNavigationAction(action)
                    } catch (e: IllegalArgumentException) {
                        logger.warn("Skipping malformed action '{}': {}", action.action, e.message)
                        null
                    }
                }
            }
        }

        val questionsState = (response.questionsState ?: emptyList()).map { qs ->
            TrackedQuestion(
                question = qs.question,
                resolved = qs.status?.lowercase() == "resolved",
                findings = qs.findings ?: emptyList()
            )
        }

        val captureRegions = response.captureRegions?.map { r ->
            CaptureRegion(
                x1 = r.x1.coerceIn(0, 1000),
                y1 = r.y1.coerceIn(0, 1000),
                x2 = r.x2.coerceIn(0, 1000),
                y2 = r.y2.coerceIn(0, 1000),
                relevance = r.relevance,
                containsTable = r.containsTable
            )
        }?.filter { it.x2 > it.x1 && it.y2 > it.y1 } ?: emptyList()

        val openCount = questionsState.count { !it.resolved }
        val resolvedCount = questionsState.count { it.resolved }
        val totalFindings = questionsState.sumOf { it.findings.size } + (response.generalFindings?.size ?: 0)
        logger.debug(
            "Navigation: decision={} actions=[{}] | questions={} (open={}, resolved={}) | findings={} | pageState={}",
            response.decision,
            actions.joinToString(", ") { it::class.simpleName ?: "?" },
            questionsState.size, openCount, resolvedCount, totalFindings,
            pageState
        )

        return WebpageNavigationOutput(
            actions = actions,
            questionsState = questionsState,
            generalFindings = response.generalFindings ?: emptyList(),
            pageState = pageState,
            observation = response.observation,
            captureRegions = captureRegions,
            tokenUsage = tokenUsage
        )
    }

    private fun buildPrompt(input: WebpageNavigationInput): String = buildString {
        appendLine("PAGE: ${input.pageTitle} — ${input.pageUrl}")
        appendLine("SCROLL: ${input.scrollPercent}%")

        if (input.pageState.isNotEmpty()) {
            appendLine()
            appendLine("PREVIOUS PAGE STATE (carry forward all; update only entries you can visually verify changed):")
            input.pageState.forEach { s ->
                appendLine("  - $s")
            }
        }

        if (input.labeledElements != null) {
            appendLine()
            appendLine("LABELED ELEMENTS (numbered — use element_label to click):")
            appendLine(input.labeledElements)
        }

        appendLine()
        appendLine("--- QUERY & CONTEXT ---")

        appendLine()
        appendLine("QUERY: ${input.query}")
        appendLine("ITERATION: ${input.currentIteration} / ${input.maxIterations}")

        if (input.questions.isNotEmpty()) {
            appendLine()
            appendLine("QUESTIONS:")
            input.questions.forEachIndexed { idx, q ->
                val tag = if (q.resolved) "RESOLVED" else "OPEN"
                appendLine("  [$tag] Q${idx + 1}. ${q.question}")
                q.findings.forEach { f ->
                    appendLine("    - $f")
                }
            }
            val allResolved = input.questions.all { it.resolved }
            if (allResolved) {
                appendLine("  All questions resolved. You may use exploration_finished.")
            }
        }

        if (input.generalFindings.isNotEmpty()) {
            appendLine()
            appendLine("GENERAL FINDINGS:")
            input.generalFindings.forEach { f ->
                appendLine("  - $f")
            }
        }

        if (input.previousActions.isNotEmpty()) {
            appendLine()
            appendLine("TURNS:")
            input.previousActions.forEachIndexed { idx, entry ->
                appendLine(formatTurnEntry(idx + 1, entry))
            }
        }
    }

    private fun toNavigationAction(resp: ActionResponse): NavigationAction {
        val reason = resp.reason ?: ""
        return when (resp.action) {
            "click" -> {
                val p = checkNotNull(resp.click) { "click action missing click params" }
                val box2d = p.box_2d?.map { it.coerceIn(0, 1000) }
                if (box2d != null) require(box2d.size == 4) { "click box_2d must have 4 elements: [ymin, xmin, ymax, xmax]" }
                require(p.element_label != null || box2d != null) { "click must have element_label or box_2d" }
                NavigationAction.Click(
                    elementLabel = p.element_label,
                    box2d = box2d,
                    label = p.label,
                    reason = reason
                )
            }
            "scroll_page" -> {
                val p = resp.scroll
                NavigationAction.Scroll(
                    scrollDirection = parseScrollDirection(p?.direction),
                    scrollPercent = (p?.percent ?: 100).coerceIn(10, 100),
                    reason = reason
                )
            }
            "scroll_element" -> {
                val p = checkNotNull(resp.scrollAt) { "scroll_element action missing scrollAt params" }
                NavigationAction.ScrollAt(
                    x = checkNotNull(p.x) { "scroll_element missing x" }.coerceIn(0, 1000),
                    y = checkNotNull(p.y) { "scroll_element missing y" }.coerceIn(0, 1000),
                    scrollDirection = parseScrollDirection(checkNotNull(p.direction) { "scroll_element missing direction" }),
                    scrollPercent = (p.percent ?: 100).coerceIn(10, 100),
                    reason = reason
                )
            }
            "find_on_page" -> NavigationAction.FindOnPage(
                keywords = resp.findOnPage?.keywords ?: emptyList(),
                reason = reason
            )
            "scroll_to_text" -> NavigationAction.ScrollToText(
                searchText = resp.scrollToText?.text ?: "",
                direction = parseScrollDirection(resp.scrollToText?.direction),
                reason = reason
            )
            "peek_full_page" -> NavigationAction.PeekFullPage(reason = reason)
            "type_text" -> {
                val p = checkNotNull(resp.type) { "type_text action missing type params" }
                NavigationAction.Type(
                    x = checkNotNull(p.x) { "type_text missing x" }.coerceIn(0, 1000),
                    y = checkNotNull(p.y) { "type_text missing y" }.coerceIn(0, 1000),
                    text = checkNotNull(p.text) { "type_text missing text" },
                    reason = reason
                )
            }
            else -> throw IllegalArgumentException("Unknown action type: ${resp.action}")
        }
    }

    private fun parseScrollDirection(direction: String?): ScrollDirection = when (direction?.uppercase()) {
        "UP" -> ScrollDirection.UP
        "LEFT" -> ScrollDirection.LEFT
        "RIGHT" -> ScrollDirection.RIGHT
        else -> ScrollDirection.DOWN
    }

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
            val target = if (action.elementLabel != null) "element#${action.elementLabel}" else "box_2d=${action.box2d}"
            "click ${action.label ?: action.reason.take(60)} $target"
        }
        is NavigationAction.Scroll -> "scroll_page ${action.scrollDirection.name.lowercase()} ${action.scrollPercent}%"
        is NavigationAction.ScrollAt -> "scroll_element (${action.x},${action.y}) ${action.scrollDirection.name.lowercase()} ${action.scrollPercent}%"
        is NavigationAction.FindOnPage -> "find_on_page [${action.keywords.joinToString(", ") { "\"$it\"" }}]"
        is NavigationAction.ScrollToText -> "scroll_to_text \"${action.searchText}\""
        is NavigationAction.PeekFullPage -> "peek_full_page"
        is NavigationAction.Type -> {
            "type_text '${action.text.take(30)}' into ${action.reason.take(60).ifEmpty { "(${action.x},${action.y})" }}"
        }
        is NavigationAction.ExplorationFinished -> {
            val summary = action.answer?.take(80) ?: "no findings"
            "exploration_finished: $summary"
        }
    }

    // --- Keyword generation for pre-scan ---

    private val keywordsOutputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(mapOf(
            "keywords" to Schema.builder()
                .type("ARRAY")
                .items(Schema.builder().type("STRING").build())
                .description("8-12 single-word search tokens likely to appear as literal text on the page. Each must be one word or symbol.")
                .build()
        ))
        .required(listOf("keywords"))
        .build()

    @Serializable
    private data class KeywordsResponse(val keywords: List<String>)

    override suspend fun generateSearchKeywords(query: String): SearchKeywordsResult {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<KeywordsResponse>(this@WebpageNavigationAgentGenAiImpl::class.simpleName!! + ".keywords") {
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(Part.fromText(
                        "Generate single-word search keywords for finding information about this query on a webpage.\n" +
                        "Rules:\n" +
                        "- Each keyword must be a SINGLE word or token (e.g. \"screening\", \"HK\$\", \"price\", \"OT&P\").\n" +
                        "- Never combine multiple words into one keyword (BAD: \"health screening price\").\n" +
                        "- Include: brand/company names, currency symbols (\$, HK\$, £, €), numbers, units, short abbreviations.\n" +
                        "- Use words likely to appear as literal text on the page, not abstract descriptions.\n\n" +
                        "Query: $query"
                    ))),
                    GenerateContentConfig.builder()
                        .temperature(1.0F)
                        .responseSchema(keywordsOutputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                                .build()
                        )
                        .build()
                )

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

        val flatKeywords = response.keywords
            .flatMap { it.split("\\s+".toRegex()) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        logger.debug("Generated {} pre-scan keywords for query '{}': {}", flatKeywords.size, query.take(60), flatKeywords)
        return SearchKeywordsResult(keywords = flatKeywords, tokenUsage = tokenUsage)
    }
}
