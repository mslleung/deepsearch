package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel

import io.deepsearch.domain.agents.ActionWithOutcome
import io.deepsearch.domain.agents.IFullPageNavigationAgent
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.agents.NavigationMode
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

    // ── Shared sub-schemas ──────────────────────────────────────────────

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
                    .description("Whether this question is still open or has been resolved. Only mark resolved when the relevant data appears in EXTRACTED KNOWLEDGE.").build()
            )
        )
        .required(listOf("question", "status"))
        .propertyOrdering(listOf("question", "status"))
        .build()

    // ── Agent 1: Navigate + Decide ──────────────────────────────────────

    private val navigateSchema: Schema = Schema.builder()
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
                    .description("The COMPLETE updated state of ALL questions. On the first turn, decompose the QUERY into sub-questions. On later turns, carry forward ALL previous questions. Mark questions resolved ONLY when the relevant data appears in EXTRACTED KNOWLEDGE.")
                    .build(),
                "decision" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("continue_exploring", "exploration_finished"))
                    .description("Top-level intent: continue exploring the page, or finish exploration.")
                    .build(),
                "relevantInfoFound" to Schema.builder()
                    .type("BOOLEAN")
                    .description("When decision is exploration_finished: true if the page contains information relevant to the query (even partial), false if no relevant information was found after exploring all relevant sections.")
                    .build(),
                "actions" to Schema.builder()
                    .type("ARRAY")
                    .items(actionSchema)
                    .description("Exploration actions to execute in order (decision=continue_exploring only). Eagerly include ALL actions that might yield information.")
                    .nullable(true)
                    .build()
            )
        )
        .required(listOf("pageState", "observation", "questionsState", "decision", "relevantInfoFound"))
        .propertyOrdering(listOf("pageState", "observation", "questionsState", "decision", "relevantInfoFound", "actions"))
        .build()

    private val fullPageNavigateInstruction = """
        You are a webpage exploration agent. You should imitate a human navigating a webpage looking for information.

        You see a screenshot of the **entire page** from top to bottom. Interactive elements are marked with **numbered labels** overlaid on the screenshot.
        You can click on any labeled element by providing its `element_label` number.
        If content is hidden (behind accordions, tabs, etc.), click the header/toggle to reveal it. You will see the updated full page on the next turn.

        ## STAGE 1 — VISUAL ANALYSIS
        Complete this stage based ONLY on what you SEE in the screenshot — before considering the query.
        - pageState: Report ALL dynamic UI states (which tab is highlighted, which sections are expanded/collapsed, which toggles are on/off). Carry forward previous entries for off-screen elements.
        - observation: Describe the page layout and any changes from the last action.
        CRITICAL: Do NOT let the query influence your visual analysis. If the query mentions "X", do not assume X is visible — look at the screenshot and report what is ACTUALLY there.

        ## STAGE 2 — QUERY PLANNING
        Now read the query and use your Stage 1 analysis to decide what to do.
        1. Trust your Stage 1 analysis. If you reported "Active tab: Y" in pageState, that IS the active tab — even if you expected a different tab based on the query or your previous click. If the state doesn't match what you expected, your last click likely targeted the wrong element — try a different one.
        2. EXTRACTED KNOWLEDGE (shown in the prompt) is the accumulated factual knowledge extracted by a separate system from the current screenshot. Use it to track progress and resolve questions.
        3. Explore the page by issuing actions: click, type_text.
        4. If a click led to navigation, it would be recorded separately, just continue exploring.
        5. Mark a question as resolved ONLY when the relevant data appears in EXTRACTED KNOWLEDGE. If you have not yet seen the data in EXTRACTED KNOWLEDGE, continue exploring.
        6. If you see tabs, accordions, or toggles that MAY contain relevant content, you MUST click each one to reveal its content before finishing. Do NOT report content as "not listed" or "not available" if you haven't clicked the tab/toggle to check.
        7. When the query asks for a LIST of items (e.g. 'all prices', 'all packages'), you MUST systematically click through ALL relevant tabs/accordions/toggles to gather every item before calling exploration_finished.

        ## Decision & Actions
        **continue_exploring**: Provide ALL exploration actions you think will yield information.
        - **type_text**: Type into input field at (x,y). Highest priority.
        - **click**: Click an interactive element by element_label (preferred) or box_2d [ymin, xmin, ymax, xmax] 0-1000 scale as fallback. Every click MUST include element_label or box_2d coordinates.

        **exploration_finished**: Stop exploring.
        - Set `relevantInfoFound: true` if the EXTRACTED KNOWLEDGE contains data relevant to the query (even partial information is valuable).
        - Set `relevantInfoFound: false` if after exploring all relevant sections, you concluded that the page does NOT contain information relevant to the query.
    """.trimIndent()

    private val overlayNavigateInstruction = """
        You are a webpage exploration agent. You should imitate a human navigating a webpage looking for information.

        You see a **viewport screenshot**. A dialog/overlay is open on the page, and the screenshot shows what is currently visible in the browser viewport. Interactive elements are marked with **numbered labels**.
        You can interact with labeled elements by providing their `element_label` number. Use `box_2d` as a fallback only when no label matches.
        Use `scroll_element` to scroll within the overlay if content is cut off at the bottom or top.
        To dismiss the overlay, click the close button (X) or click on the dimmed/empty space outside the overlay.

        ## STAGE 1 — VISUAL ANALYSIS
        Complete this stage based ONLY on what you SEE in the screenshot — before considering the query.
        - pageState: Report ALL dynamic UI states (which tab is highlighted, which sections are expanded/collapsed, which toggles are on/off). Carry forward previous entries for off-screen elements.
        - observation: Describe the overlay content and any changes from the last action. Note if content appears cut off (indicating more content available via scrolling).
        CRITICAL: Do NOT let the query influence your visual analysis. If the query mentions "X", do not assume X is visible — look at the screenshot and report what is ACTUALLY there.

        ## STAGE 2 — QUERY PLANNING
        Now read the query and use your Stage 1 analysis to decide what to do.
        1. Trust your Stage 1 analysis. If you reported "Active tab: Y" in pageState, that IS the active tab — even if you expected a different tab based on the query or your previous click. If the state doesn't match what you expected, your last click likely targeted the wrong element — try a different one.
        2. EXTRACTED KNOWLEDGE (shown in the prompt) is the accumulated factual knowledge extracted by a separate system from the current screenshot. Use it to track progress and resolve questions.
        3. Explore the page by issuing actions: click, type_text, scroll_element.
        4. If a click led to navigation, it would be recorded separately, just continue exploring.
        5. Mark a question as resolved ONLY when the relevant data appears in EXTRACTED KNOWLEDGE. If you have not yet seen the data in EXTRACTED KNOWLEDGE, continue exploring.
        6. If the overlay does NOT contain the information you need, dismiss it by clicking the close button (X) or clicking the dimmed area outside, then continue exploring the main page.
        7. Do NOT call exploration_finished while inside an overlay unless all required data has been found.

        ## Decision & Actions
        **continue_exploring**: Provide ALL exploration actions you think will yield information.
        - **type_text**: Type into input field at (x,y). Highest priority.
        - **click**: Click an interactive element by element_label (preferred) or box_2d [ymin, xmin, ymax, xmax] 0-1000 scale as fallback. Every click MUST include element_label or box_2d coordinates.
        - **scroll_element**: Scroll within an overlay or container. Provide x,y coordinates of the scrollable area and the direction (DOWN, UP, LEFT, RIGHT).

        **exploration_finished**: Stop exploring.
        - Set `relevantInfoFound: true` if the EXTRACTED KNOWLEDGE contains data relevant to the query (even partial information is valuable).
        - Set `relevantInfoFound: false` if after exploring all relevant sections, you concluded that the page does NOT contain information relevant to the query.
    """.trimIndent()

    // ── Serializable response types ─────────────────────────────────────

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
        val status: String? = null
    )

    @Serializable
    private data class NavigateResponse(
        val pageState: List<String>? = null,
        val observation: String? = null,
        val questionsState: List<QuestionStateResponse>? = null,
        val decision: String? = null,
        val relevantInfoFound: Boolean? = null,
        val actions: List<ActionResponse>? = null
    )

    // ── generate() — navigate + decide ────────────────────────────────

    override suspend fun generate(input: FullPageNavigationInput): FullPageNavigationOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val prompt = buildNavigatePrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<NavigateResponse>(this@FullPageNavigationAgentGenAiImpl::class.simpleName!! + ".navigate") {
                val screenshotLabel = when (input.navigationMode) {
                    NavigationMode.VIEWPORT -> "VIEWPORT SCREENSHOT (overlay detected):"
                    NavigationMode.FULL_PAGE -> "FULL PAGE SCREENSHOT:"
                }
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
                        .responseSchema(navigateSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(
                            when (input.navigationMode) {
                                NavigationMode.VIEWPORT -> overlayNavigateInstruction
                                NavigationMode.FULL_PAGE -> fullPageNavigateInstruction
                            }
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
        logger.debug("Navigate: pageState={}", pageState)

        val actions = (response.actions ?: emptyList()).mapNotNull { action ->
            try {
                toNavigationAction(action)
            } catch (e: IllegalArgumentException) {
                logger.warn("Skipping malformed action '{}': {}", action.action, e.message)
                null
            }
        }

        val questionsState = (response.questionsState ?: emptyList()).map { qs ->
            TrackedQuestion(
                question = qs.question,
                resolved = qs.status?.lowercase() == "resolved"
            )
        }

        val decision = response.decision ?: "continue_exploring"
        val openCount = questionsState.count { !it.resolved }
        val resolvedCount = questionsState.count { it.resolved }

        logger.debug(
            "Navigate: decision={} actions=[{}] | questions={} (open={}, resolved={}) | pageState={}",
            decision,
            actions.joinToString(", ") { it::class.simpleName ?: "?" },
            questionsState.size, openCount, resolvedCount,
            pageState
        )

        return FullPageNavigationOutput(
            actions = actions,
            questions = questionsState,
            pageState = pageState,
            observation = response.observation,
            decision = decision,
            relevantInfoFound = response.relevantInfoFound,
            tokenUsage = tokenUsage
        )
    }

    // ── Prompt builders ─────────────────────────────────────────────────

    private fun buildNavigatePrompt(input: FullPageNavigationInput): String = buildString {
        appendLine("PAGE: ${input.pageTitle} — ${input.pageUrl}")

        if (input.pageState.isNotEmpty()) {
            appendLine()
            appendLine("PREVIOUS PAGE STATE (carry forward all; update only entries you can visually verify changed):")
            input.pageState.forEach { s ->
                appendLine("  - $s")
            }
        }

        if (!input.contentObservation.isNullOrBlank()) {
            appendLine()
            appendLine("CONTENT OBSERVATION (from clean screenshot analysis):")
            appendLine("  ${input.contentObservation}")
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
            }
        }

        if (input.extractedRegionContent.isNotEmpty()) {
            appendLine()
            appendLine("EXTRACTED KNOWLEDGE:")
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
            val turns = groupIntoTurns(input.previousActions)
            turns.forEachIndexed { idx, turn ->
                appendLine(formatTurn(idx + 1, turn))
            }
        }
    }

    // ── Shared helpers ──────────────────────────────────────────────────

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

    private fun groupIntoTurns(actions: List<ActionWithOutcome>): List<List<ActionWithOutcome>> {
        val turns = mutableListOf<MutableList<ActionWithOutcome>>()
        for (entry in actions) {
            if (entry.observation != null || turns.isEmpty()) {
                turns.add(mutableListOf(entry))
            } else {
                turns.last().add(entry)
            }
        }
        return turns
    }

    private fun formatTurn(turnNumber: Int, entries: List<ActionWithOutcome>): String = buildString {
        val first = entries.first()
        appendLine("  [Turn $turnNumber] observation: ${first.observation ?: "(none)"}")
        for ((i, entry) in entries.withIndex()) {
            append("      ${i + 1}. ${formatActionDesc(entry.action)}")
            if (entry.outcome != null) append(" -> ${entry.outcome}")
            if (i < entries.lastIndex) appendLine()
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
