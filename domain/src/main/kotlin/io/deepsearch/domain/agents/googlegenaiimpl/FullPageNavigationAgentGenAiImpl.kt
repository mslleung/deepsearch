package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema

import io.deepsearch.domain.agents.ActionWithOutcome
import io.deepsearch.domain.agents.ExplorationDirection
import io.deepsearch.domain.agents.IFullPageNavigationAgent
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.agents.NavigationMode
import io.deepsearch.domain.agents.ScrollDirection
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

    // ── Sub-schemas ─────────────────────────────────────────────────────

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
                        "elementLabel" to Schema.builder().type("INTEGER")
                            .description("The numbered label [N] visible on the screenshot for the element to click.")
                            .build(),
                        "target" to Schema.builder().type("STRING")
                            .description("Brief description of the element for logging (e.g. 'Learn More under Well Woman').")
                            .build()
                    ))
                    .required(listOf("elementLabel"))
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

    private val directionSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "direction" to Schema.builder().type("STRING")
                    .description("A distinct area, tab, section, or navigation path worth exploring.")
                    .build(),
                "status" to Schema.builder().type("STRING")
                    .enum_(listOf("unexplored", "exploring", "exhausted"))
                    .description("unexplored: not yet tried. exploring: currently being investigated. exhausted: fully explored or confirmed irrelevant.")
                    .build()
            )
        )
        .required(listOf("direction", "status"))
        .propertyOrdering(listOf("direction", "status"))
        .build()

    // ── Combined navigate + plan schema ─────────────────────────────────

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
                "explorationDirections" to Schema.builder()
                    .type("ARRAY")
                    .items(directionSchema)
                    .description("ALL exploration directions identified on the page, sorted by relevance to the query (most promising first). Carry forward previous directions and add any new ones discovered.")
                    .build(),
                "currentDirection" to Schema.builder()
                    .type("STRING")
                    .nullable(true)
                    .description("The specific direction your actions are for. Null only when searchComplete=true.")
                    .build(),
                "searchComplete" to Schema.builder()
                    .type("BOOLEAN")
                    .description("True when the EXTRACTED KNOWLEDGE already contains sufficient information to answer the query. Set true when: (1) the core question is answered in the extracted content, OR (2) all directions are exhausted. Remaining unexplored directions that are unlikely to add important new information should NOT prevent completion.")
                    .build(),
                "allDirectionsExhausted" to Schema.builder()
                    .type("BOOLEAN")
                    .description("True only when EVERY direction has been explored and confirmed irrelevant or fully extracted.")
                    .build(),
                "actions" to Schema.builder()
                    .type("ARRAY")
                    .items(actionSchema)
                    .description("Actions to execute in order. Include ALL actions that advance the current direction. Empty array when searchComplete=true. When switching to a new direction, include the first actions for the new direction immediately.")
                    .nullable(true)
                    .build()
            )
        )
        .required(listOf("pageState", "observation", "explorationDirections", "searchComplete", "allDirectionsExhausted"))
        .propertyOrdering(listOf("pageState", "observation", "explorationDirections", "currentDirection", "searchComplete", "allDirectionsExhausted", "actions"))
        .build()

    private val fullPageNavigateInstruction = """
        You are a webpage exploration and planning agent. You both PLAN which directions to explore and EXECUTE navigation actions on the page.

        You see a screenshot of the **entire page** from top to bottom.
        Interactive elements (buttons, links, tabs, toggles) are highlighted with colored bounding boxes and **numbered labels** like [0], [1], [2], etc.
        To click an element, provide its label number in `elementLabel`. If content is hidden (behind accordions, tabs, etc.), click the header/toggle to reveal it. You will see the updated full page on the next turn.

        ## YOUR TWO RESPONSIBILITIES

        ### 1. DIRECTION PLANNING (strategic)
        Identify and track exploration directions on the page — these are distinct areas, tabs, sections, or navigation paths that could contain information relevant to the query.

        **Direction Identification:**
        Look at the screenshot. Identify elements that could reveal NEW content when clicked:
        - Tabs visible in the screenshot (list each one individually by its exact text)
        - Accordions, toggles, expandable sections
        - Product cards, category links, "Learn more" buttons
        - Navigation menu items that switch content on the same page

        CRITICAL: List tabs even if their names seem unrelated to the query. Content is often categorized in non-obvious ways.
        Sort directions by relevance to the query (most promising first).

        **Direction Status Tracking:**
        Review the ACTION HISTORY to determine each direction's status:
        - unexplored: no action has been taken for this direction
        - exploring: actions are being taken for this direction
        - exhausted: action was taken AND outcome confirms it's done (off-page link, no relevant content, content already extracted)

        ONLY mark 'exhausted' if the ACTION HISTORY contains actions matching this direction AND the outcome shows it's irrelevant or complete.

        **Search Completion Evaluation:**
        After updating directions, evaluate the EXTRACTED KNOWLEDGE against the query:

        Set searchComplete=true when:
        - The EXTRACTED KNOWLEDGE contains a clear, direct answer to the query
        - The core question is answered even if there are unexplored directions about unrelated topics
        - All directions have been exhausted (nothing more to try)

        Set searchComplete=false when:
        - No content has been extracted yet (EXTRACTED KNOWLEDGE is empty)
        - The extracted content does NOT answer the query
        - There are promising unexplored directions that could contain the answer

        CRITICAL: Do NOT keep exploring just because unexplored directions exist. If the answer is already in the EXTRACTED KNOWLEDGE, stop immediately. Efficiency matters.

        ### 2. NAVIGATION EXECUTION (tactical)
        Execute actions to explore the current direction — click the right elements, scroll to reveal content.

        When the current direction is done (content visible or confirmed irrelevant), switch to the next promising direction immediately:
        - Mark the old direction as 'exhausted'
        - Set the new direction as 'exploring'
        - Provide actions for the new direction in the same response

        ## CRITICAL: SCREENSHOT vs. EXTRACTED KNOWLEDGE
        - The SCREENSHOT is for identifying DIRECTIONS and deciding which elements to click
        - The EXTRACTED KNOWLEDGE list is the SOLE BASIS for your searchComplete decision
        - Even if you can SEE an answer on the screenshot, it does NOT count unless it appears in the EXTRACTED KNOWLEDGE list
        - Content visible on screen has NOT been captured until it shows up in EXTRACTED KNOWLEDGE

        ## How to respond
        1. **pageState**: Report ALL dynamic UI states based ONLY on what you SEE (which tab is highlighted, which sections are expanded/collapsed). Carry forward previous entries for off-screen elements. Do NOT let the query influence this — report what is ACTUALLY there.
        2. **observation**: Describe the page layout and any changes from the last action.
        3. **explorationDirections**: ALL directions with updated statuses. Carry forward previous directions. Add new ones discovered.
        4. **currentDirection**: Which direction your actions are for. Null only when searchComplete=true.
        5. **searchComplete / allDirectionsExhausted**: Your planning decisions.
        6. **actions**: The navigation actions to execute. Empty when searchComplete=true.

        ## CLICKING ELEMENTS
        Reference elements by their numbered label [N] on the screenshot. Provide the number in `elementLabel`.
        If you cannot find a suitable labeled element for what you need to click, use -1 and describe the element in `target`.

        ## RULES
        - NEVER set allDirectionsExhausted=true while ANY direction has status 'unexplored'
        - Carry forward ALL previous directions — do NOT drop any
        - When switching directions, include the first actions for the new direction immediately
    """.trimIndent()

    private val overlayNavigateInstruction = """
        You are a webpage exploration and planning agent. You both PLAN which directions to explore and EXECUTE navigation actions on the page.

        You see a **viewport screenshot**. A dialog/overlay is open on the page, and the screenshot shows what is currently visible in the browser viewport.
        Interactive elements are highlighted with colored bounding boxes and **numbered labels** like [0], [1], [2], etc.
        To click an element, provide its label number in `elementLabel`.
        Use `scroll_element` to scroll within the overlay if content is cut off at the bottom or top.
        To dismiss the overlay, click the close button (X) or click on the dimmed/empty space outside the overlay.

        ## YOUR TWO RESPONSIBILITIES

        ### 1. DIRECTION PLANNING (strategic)
        Track exploration directions and evaluate search completion based on EXTRACTED KNOWLEDGE.

        Set searchComplete=true when:
        - The EXTRACTED KNOWLEDGE contains a clear, direct answer to the query
        - All directions have been exhausted

        Set searchComplete=false when:
        - No content has been extracted yet
        - The extracted content does NOT answer the query
        - There are promising unexplored directions

        CRITICAL: The EXTRACTED KNOWLEDGE list is the SOLE BASIS for searchComplete. Content visible on screen has NOT been captured until it appears in EXTRACTED KNOWLEDGE.

        ### 2. NAVIGATION EXECUTION (tactical)
        Execute actions to explore the current direction within the overlay. When the current direction is done, switch to the next promising direction immediately.

        ## How to respond
        1. **pageState**: Report ALL dynamic UI states based ONLY on what you SEE. Carry forward previous entries. Do NOT let the query influence this.
        2. **observation**: Describe the overlay content and any changes. Note if content appears cut off.
        3. **explorationDirections**: ALL directions with updated statuses.
        4. **currentDirection**: Which direction your actions are for. Null when searchComplete=true.
        5. **searchComplete / allDirectionsExhausted**: Your planning decisions.
        6. **actions**: Use `scroll_element` for scrolling within the overlay. Empty when searchComplete=true.

        ## CLICKING ELEMENTS
        Reference elements by their numbered label [N] on the screenshot. Provide the number in `elementLabel`.
        If you cannot find a suitable labeled element for what you need to click, use -1 and describe the element in `target`.

        ## RULES
        - NEVER set allDirectionsExhausted=true while ANY direction has status 'unexplored'
        - Carry forward ALL previous directions — do NOT drop any
    """.trimIndent()

    // ── Serializable response types ─────────────────────────────────────

    @Serializable
    private data class ClickParams(val elementLabel: Int? = null, val target: String? = null)

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
    private data class DirectionResponse(
        val direction: String,
        val status: String? = null
    )

    @Serializable
    private data class NavigateResponse(
        val pageState: List<String>? = null,
        val observation: String? = null,
        val explorationDirections: List<DirectionResponse>? = null,
        val currentDirection: String? = null,
        val searchComplete: Boolean? = null,
        val allDirectionsExhausted: Boolean? = null,
        val actions: List<ActionResponse>? = null
    )

    // ── generate() ──────────────────────────────────────────────────────

    override suspend fun generate(input: FullPageNavigationInput): FullPageNavigationOutput {
        val modelId = ModelIds.GEMINI_3_5_FLASH.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val prompt = buildNavigatePrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<NavigateResponse>(this@FullPageNavigationAgentGenAiImpl::class.simpleName!! + ".navigate") {
                val screenshotLabel = when (input.navigationMode) {
                    NavigationMode.VIEWPORT -> "VIEWPORT SCREENSHOT (overlay detected):"
                    NavigationMode.FULL_PAGE -> "FULL PAGE SCREENSHOT:"
                }
                val sysInstruction = when (input.navigationMode) {
                    NavigationMode.VIEWPORT -> overlayNavigateInstruction
                    NavigationMode.FULL_PAGE -> fullPageNavigateInstruction
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
                        .temperature(1.0f)
                        .responseSchema(navigateSchema)
                        .responseMimeType("application/json")
                        .systemInstruction(Content.fromParts(Part.fromText(sysInstruction)))
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
        val directions = (response.explorationDirections ?: emptyList()).map { d ->
            ExplorationDirection(
                direction = d.direction,
                status = d.status ?: "unexplored"
            )
        }
        val allExhausted = response.allDirectionsExhausted ?: false
        val unexploredCount = directions.count { it.status == "unexplored" }
        val searchComplete = response.searchComplete ?: false

        val actions = (response.actions ?: emptyList()).mapNotNull { action ->
            try {
                toNavigationAction(action)
            } catch (e: IllegalArgumentException) {
                logger.warn("Skipping malformed action '{}': {}", action.action, e.message)
                null
            }
        }

        logger.debug(
            "Navigate: directions={} (unexplored={}) | searchComplete={} | allExhausted={} | currentDir={} | actions=[{}] | pageState={}",
            directions.size, unexploredCount, searchComplete, allExhausted,
            response.currentDirection?.take(60),
            actions.joinToString(", ") { it::class.simpleName ?: "?" },
            pageState
        )

        return FullPageNavigationOutput(
            actions = actions,
            pageState = pageState,
            observation = response.observation,
            explorationDirections = directions,
            currentDirection = response.currentDirection,
            searchComplete = searchComplete || (allExhausted && unexploredCount == 0),
            allDirectionsExhausted = allExhausted && unexploredCount == 0,
            tokenUsage = tokenUsage
        )
    }

    // ── Prompt builder ──────────────────────────────────────────────────

    private fun buildNavigatePrompt(input: FullPageNavigationInput): String = buildString {
        appendLine("PAGE: ${input.pageTitle} — ${input.pageUrl}")
        appendLine("ITERATION: ${input.currentIteration} / ${input.maxIterations}")

        if (input.pageState.isNotEmpty()) {
            appendLine()
            appendLine("PREVIOUS PAGE STATE (carry forward all; update only entries you can visually verify changed):")
            input.pageState.forEach { s ->
                appendLine("  - $s")
            }
        }

        appendLine()
        appendLine("QUERY: ${input.query}")

        if (input.explorationDirections.isNotEmpty()) {
            appendLine()
            appendLine("CURRENT EXPLORATION DIRECTIONS:")
            input.explorationDirections.forEachIndexed { idx, d ->
                appendLine("  [${d.status.uppercase()}] D${idx + 1}. ${d.direction}")
            }
        }

        if (input.extractedContent.isNotEmpty()) {
            appendLine()
            appendLine("EXTRACTED KNOWLEDGE (already captured — basis for searchComplete decision):")
            input.extractedContent.forEach { ec ->
                appendLine("  [${ec.description}]: ${ec.text.take(200)}")
            }
        }

        if (input.previousActions.isNotEmpty()) {
            appendLine()
            appendLine("TURNS (current direction):")
            val turns = groupIntoTurns(input.previousActions)
            turns.forEachIndexed { idx, turn ->
                appendLine(formatTurn(idx + 1, turn))
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun toNavigationAction(resp: ActionResponse): NavigationAction {
        val reason = resp.reason ?: ""
        return when (resp.action) {
            "click" -> {
                val p = checkNotNull(resp.click) { "click action missing click params" }
                val label = checkNotNull(p.elementLabel) { "click must have elementLabel" }
                NavigationAction.Click(
                    elementLabel = label,
                    reason = reason,
                    target = p.target
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
            val desc = action.target ?: "[${action.elementLabel}]"
            "click $desc (label=${action.elementLabel}, reason: ${action.reason})"
        }
        is NavigationAction.Type -> {
            "type_text '${action.text}' into ${action.reason.ifEmpty { "(${action.x},${action.y})" }}"
        }
        is NavigationAction.ScrollAt -> {
            "scroll_element (${action.x},${action.y}) ${action.scrollDirection} ${action.scrollPercent}%"
        }
        is NavigationAction.ExplorationFinished -> {
            val summary = action.answer ?: "no findings"
            "exploration_finished: $summary"
        }
        else -> action.toString()
    }
}
