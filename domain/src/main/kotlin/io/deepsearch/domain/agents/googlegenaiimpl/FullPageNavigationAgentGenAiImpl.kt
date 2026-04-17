package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel

import io.deepsearch.domain.agents.ActionWithOutcome
import io.deepsearch.domain.agents.CaptureRegion
import io.deepsearch.domain.agents.IFullPageNavigationAgent
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.agents.ScrollDirection
import io.deepsearch.domain.agents.TrackedQuestion
import io.deepsearch.domain.agents.FullPageNavigationInput
import io.deepsearch.domain.agents.FullPageNavigationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class FullPageNavigationAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IFullPageNavigationAgent {

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
                    .enum_(listOf("click", "type_text", "scroll_element"))
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
                "type" to Schema.builder()
                    .type("OBJECT")
                    .nullable(true)
                    .properties(mapOf(
                        "x" to Schema.builder().type("INTEGER").description("X coordinate of input field (0-1000).").build(),
                        "y" to Schema.builder().type("INTEGER").description("Y coordinate of input field (0-1000).").build(),
                        "text" to Schema.builder().type("STRING").description("Text to type.").build()
                    ))
                    .required(listOf("x", "y", "text"))
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
                    .build()
            )
        )
        .required(listOf("action", "reason"))
        .propertyOrdering(listOf("action", "reason", "click", "type", "scrollAt"))
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
                    .description("Bounding boxes of regions containing data relevant to the query (prices, numbers, text content, tables, charts). The system will programmatically extract text from these regions. You MUST provide capture regions for any area with factual data needed to answer the query. Use 0-1000 coordinates.")
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
        .required(listOf("pageState", "observation", "questionsState", "generalFindings", "decision", "reason"))
        .propertyOrdering(listOf("pageState", "observation", "questionsState", "generalFindings", "captureRegions", "decision", "reason", "actions", "answer"))
        .build()

    private val fullPageSystemInstruction = """
        You are a webpage exploration agent. You work in two stages within each response.

        You see a screenshot of the **entire page** from top to bottom. Interactive elements are marked with **numbered labels** overlaid on the screenshot.
        You can click on any labeled element by providing its `element_label` number. The system will handle scrolling automatically.
        If content is hidden (behind accordions, tabs, etc.), click the header/toggle to reveal it. You will see the updated full page on the next turn.

        ## STAGE 1 — VISUAL ANALYSIS
        Complete this stage based ONLY on what you SEE in the screenshot — before considering the query.
        - pageState: Report ALL dynamic UI states (which tab is highlighted, which sections are expanded/collapsed, which toggles are on/off). Carry forward previous entries for off-screen elements.
        - observation: Describe the page layout and any changes from the last action.
        - captureRegions: For any area containing data relevant to the query (prices, numbers, text, tables), provide bounding boxes. The system will programmatically extract text from these regions and feed it back as PROGRAMMATIC EXTRACTION on the next turn. You MUST provide capture regions for all areas with factual data needed to answer the query.
        CRITICAL: Do NOT let the query influence your visual analysis. If the query mentions "X", do not assume X is visible — look at the screenshot and report what is ACTUALLY there.

        ## STAGE 2 — QUERY PLANNING
        Now read the query and use your Stage 1 analysis to decide what to do.
        1. Trust your Stage 1 analysis. If you reported "Active tab: Y" in pageState, that IS the active tab — even if you expected a different tab based on the query or your previous click. If the state doesn't match what you expected, your last click likely targeted the wrong element — try a different one.
        2. Extract any relevant findings from your observation and PROGRAMMATIC EXTRACTION data. Do not omit data. Do not invent or modify facts.
        3. Explore the page by issuing actions: click, type_text.
        4. If a click led to navigation, it would be recorded separately, just continue exploring.
        5. Before calling exploration_finished: VERIFY that every data point in your answer appears in PROGRAMMATIC EXTRACTION data across turns. If any data point is NOT in your extractions, continue exploring to find it — do NOT guess or fabricate.
        6. If you see tabs, accordions, or toggles that MAY contain relevant content, you MUST click each one to reveal its content before finishing. Do NOT report content as "not listed" or "not available" if you haven't clicked the tab/toggle to check.
        7. When the query asks for a LIST of items (e.g. 'all prices', 'all packages'), you MUST systematically click through ALL relevant tabs/accordions/toggles to gather every item before calling exploration_finished.

        ## Decision & Actions
        **continue_exploring**: Provide ALL exploration actions you think will yield information.
        - **type_text**: Type into input field at (x,y). Highest priority.
        - **click**: Click an interactive element by element_label (preferred) or box_2d [ymin, xmin, ymax, xmax] 0-1000 scale as fallback. Every click MUST include element_label or box_2d coordinates.

        **exploration_finished**: Stop exploring. You MUST provide a comprehensive answer in the "answer" field, synthesized from ALL findings.
        - Your answer MUST be grounded in PROGRAMMATIC EXTRACTION data. NEVER fabricate, estimate, or infer values you have not seen in the extractions.
        - If you haven't found sufficient data, continue exploring rather than guessing.
        - Never leave answer empty when you have findings.
    """.trimIndent()

    private val overlaySystemInstruction = """
        You are a webpage exploration agent. You work in two stages within each response.

        You see a **viewport screenshot**. A dialog/overlay is open on the page, and the screenshot shows what is currently visible in the browser viewport. Interactive elements are marked with **numbered labels**.
        You can interact with labeled elements by providing their `element_label` number. Use `box_2d` as a fallback only when no label matches.
        Use `scroll_element` to scroll within the overlay if content is cut off at the bottom or top.
        To dismiss the overlay, click the close button (X) or click on the dimmed/empty space outside the overlay.

        ## STAGE 1 — VISUAL ANALYSIS
        Complete this stage based ONLY on what you SEE in the screenshot — before considering the query.
        - pageState: Report ALL dynamic UI states (which tab is highlighted, which sections are expanded/collapsed, which toggles are on/off). Carry forward previous entries for off-screen elements.
        - observation: Describe the overlay content and any changes from the last action. Note if content appears cut off (indicating more content available via scrolling).
        - captureRegions: For any area containing data relevant to the query (prices, numbers, text, tables), provide bounding boxes. The system will programmatically extract text from these regions and feed it back as PROGRAMMATIC EXTRACTION on the next turn. You MUST provide capture regions for all areas with factual data needed to answer the query.
        CRITICAL: Do NOT let the query influence your visual analysis. If the query mentions "X", do not assume X is visible — look at the screenshot and report what is ACTUALLY there.

        ## STAGE 2 — QUERY PLANNING
        Now read the query and use your Stage 1 analysis to decide what to do.
        1. Trust your Stage 1 analysis. If you reported "Active tab: Y" in pageState, that IS the active tab — even if you expected a different tab based on the query or your previous click. If the state doesn't match what you expected, your last click likely targeted the wrong element — try a different one.
        2. Extract any relevant findings from your observation and PROGRAMMATIC EXTRACTION data. Do not omit data. Do not invent or modify facts.
        3. Explore the page by issuing actions: click, type_text, scroll_element.
        4. If a click led to navigation, it would be recorded separately, just continue exploring.
        5. Before calling exploration_finished: VERIFY that every data point in your answer appears in PROGRAMMATIC EXTRACTION data across turns. If any data point is NOT in your extractions, continue exploring to find it — do NOT guess or fabricate.
        6. If the overlay does NOT contain the information you need, dismiss it by clicking the close button (X) or clicking the dimmed area outside, then continue exploring the main page.
        7. Do NOT call exploration_finished while inside an overlay unless all required data has been found.

        ## Decision & Actions
        **continue_exploring**: Provide ALL exploration actions you think will yield information.
        - **type_text**: Type into input field at (x,y). Highest priority.
        - **click**: Click an interactive element by element_label (preferred) or box_2d [ymin, xmin, ymax, xmax] 0-1000 scale as fallback. Every click MUST include element_label or box_2d coordinates.
        - **scroll_element**: Scroll within an overlay or container. Provide x,y coordinates of the scrollable area and the direction (DOWN, UP, LEFT, RIGHT).

        **exploration_finished**: Stop exploring. You MUST provide a comprehensive answer in the "answer" field, synthesized from ALL findings.
        - Your answer MUST be grounded in PROGRAMMATIC EXTRACTION data. NEVER fabricate, estimate, or infer values you have not seen in the extractions.
        - If you haven't found sufficient data, continue exploring rather than guessing.
        - Never leave answer empty when you have findings.
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
    private data class ClickParams(val element_label: Int? = null, val box_2d: List<Int>? = null, val label: String? = null)

    @Serializable
    private data class TypeParams(val x: Int? = null, val y: Int? = null, val text: String? = null)

    @Serializable
    private data class ScrollAtParams(val x: Int? = null, val y: Int? = null, val direction: String? = null, val percent: Int? = null)

    @Serializable
    private data class ActionResponse(
        val action: String,
        val reason: String? = null,
        val click: ClickParams? = null,
        val type: TypeParams? = null,
        val scrollAt: ScrollAtParams? = null
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
        val questionsState: List<QuestionStateResponse>? = null,
        val generalFindings: List<String>? = null,
        val captureRegions: List<CaptureRegionResponse>? = null,
        val decision: String? = null,
        val reason: String? = null,
        val actions: List<ActionResponse>? = null,
        val answer: String? = null
    )

    override suspend fun generate(input: FullPageNavigationInput): FullPageNavigationOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val prompt = buildPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<NavigationResponse>(this@FullPageNavigationAgentGenAiImpl::class.simpleName!! + ".navigate") {
                val screenshotLabel = if (input.isOverlayMode) "VIEWPORT SCREENSHOT (overlay detected):" else "FULL PAGE SCREENSHOT:"
                val contentParts = listOf(
                    Part.fromText(screenshotLabel),
                    Part.fromBytes(input.fullPageScreenshot.bytes, input.fullPageScreenshot.mimeType.value),
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
                        .systemInstruction(Content.fromParts(Part.fromText(
                            if (input.isOverlayMode) overlaySystemInstruction else fullPageSystemInstruction
                        )))
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
                relevance = r.relevance
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

        return FullPageNavigationOutput(
            actions = actions,
            questionsState = questionsState,
            generalFindings = response.generalFindings ?: emptyList(),
            pageState = pageState,
            observation = response.observation,
            captureRegions = captureRegions,
            tokenUsage = tokenUsage
        )
    }

    private fun buildPrompt(input: FullPageNavigationInput): String = buildString {
        appendLine("PAGE: ${input.pageTitle} — ${input.pageUrl}")

        if (input.pageState.isNotEmpty()) {
            appendLine()
            appendLine("PREVIOUS PAGE STATE (carry forward all; update only entries you can visually verify changed):")
            input.pageState.forEach { s ->
                appendLine("  - $s")
            }
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

        if (input.extractedRegionContent.isNotEmpty()) {
            appendLine()
            appendLine("PROGRAMMATIC EXTRACTION (authoritative — extracted from DOM):")
            input.extractedRegionContent.forEach { ec ->
                appendLine("  [${ec.description}]${if (ec.isTable) " (table)" else ""}:")
                appendLine("    ${ec.text}")
            }
        }

        if (input.scrollStateHint != null) {
            appendLine()
            appendLine("SCROLL STATE:")
            appendLine(input.scrollStateHint)
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
            "type_text" -> {
                val p = checkNotNull(resp.type) { "type_text action missing type params" }
                NavigationAction.Type(
                    x = checkNotNull(p.x) { "type_text missing x" }.coerceIn(0, 1000),
                    y = checkNotNull(p.y) { "type_text missing y" }.coerceIn(0, 1000),
                    text = checkNotNull(p.text) { "type_text missing text" },
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
            else -> throw IllegalArgumentException("Unknown action type: ${resp.action}")
        }
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

    private fun parseScrollDirection(direction: String?): ScrollDirection = when (direction?.uppercase()) {
        "UP" -> ScrollDirection.UP
        "LEFT" -> ScrollDirection.LEFT
        "RIGHT" -> ScrollDirection.RIGHT
        else -> ScrollDirection.DOWN
    }

    private fun formatActionDesc(action: NavigationAction): String = when (action) {
        is NavigationAction.Click -> {
            val target = if (action.elementLabel != null) "element_label=${action.elementLabel}" else "box_2d=${action.box2d}"
            "click ${action.label ?: action.reason.take(60)} $target"
        }
        is NavigationAction.Type -> {
            "type_text '${action.text.take(30)}' into ${action.reason.take(60).ifEmpty { "(${action.x},${action.y})" }}"
        }
        is NavigationAction.ScrollAt -> {
            "scroll_element (${action.x},${action.y}) ${action.scrollDirection} ${action.scrollPercent}%"
        }
        is NavigationAction.ExplorationFinished -> {
            val summary = action.answer?.take(80) ?: "no findings"
            "exploration_finished: $summary"
        }
        else -> action.toString()
    }
}
