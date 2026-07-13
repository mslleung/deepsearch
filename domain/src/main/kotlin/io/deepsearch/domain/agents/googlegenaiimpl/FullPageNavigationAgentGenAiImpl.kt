package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema

import io.deepsearch.domain.agents.ActionWithOutcome
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
                        "target" to Schema.builder().type("STRING")
                            .description("Highly specific description of the element to click. Include exact visible text, the section heading directly above it, what's below it, and its vertical position on the page.")
                            .build(),
                        "roughY" to Schema.builder().type("INTEGER")
                            .description("Approximate vertical position of the element on the page, normalized 0-1000 (0=very top, 500=middle, 1000=very bottom). This is a coarse estimate used for cropping.")
                            .build()
                    ))
                    .required(listOf("target", "roughY"))
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
                "decision" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("continue_exploring", "exploration_finished"))
                    .description("continue_exploring: there are actions to execute for the current direction. exploration_finished: the current direction is fully explored (content extracted or confirmed irrelevant).")
                    .build(),
                "actions" to Schema.builder()
                    .type("ARRAY")
                    .items(actionSchema)
                    .description("Exploration actions to execute in order (decision=continue_exploring only). Eagerly include ALL actions that might yield information for the current direction.")
                    .nullable(true)
                    .build()
            )
        )
        .required(listOf("pageState", "observation", "decision"))
        .propertyOrdering(listOf("pageState", "observation", "decision", "actions"))
        .build()

    private val fullPageNavigateInstruction = """
        You are a webpage exploration agent. You execute a specific exploration direction on a webpage.

        You see a screenshot of the **entire page** from top to bottom.
        Interactive elements (buttons, links, tabs, toggles) are highlighted with colored bounding boxes on the screenshot.
        To click an element, describe it in the `target` field. A separate system will locate the exact element from your description.
        If content is hidden (behind accordions, tabs, etc.), click the header/toggle to reveal it. You will see the updated full page on the next turn.

        ## YOUR ROLE
        A separate planning agent decides WHICH directions to explore and WHEN to stop the search.
        Your job is to execute the CURRENT DIRECTION given to you in the prompt.
        Focus on thoroughly exploring that one direction — click the right elements, scroll to reveal content, and report when you're done with it.

        ## How to respond
        1. **pageState**: Report ALL dynamic UI states based ONLY on what you SEE (which tab is highlighted, which sections are expanded/collapsed, which toggles are on/off). Carry forward previous entries for off-screen elements. CRITICAL: Do NOT let the query influence this — report what is ACTUALLY there.
        2. **observation**: Describe the page layout and any changes from the last action.
        3. **decision**: `continue_exploring` if there are actions to execute for the current direction. `exploration_finished` if the direction is fully explored (relevant content is visible on screen, or confirmed irrelevant).
        4. **actions** (when continuing): The actions to execute. Eagerly include ALL actions that might yield information for the current direction.

        ## TARGET DESCRIPTION FORMAT (CRITICAL)
        Your `target` description must be EXTREMELY specific and unambiguous. Always include ALL of the following:
        1. The element's exact visible text (in quotes)
        2. The section heading DIRECTLY above the element (in quotes)
        3. What text or element is DIRECTLY below it
        4. Its vertical position on the page: "in the top quarter / upper-middle / center / lower-middle / bottom quarter"
        5. If there are other elements with the same text on the page, state how many you see and which one you mean

        Also provide `roughY`: your estimate of the element's vertical position as 0-1000 (0=very top of page, 500=middle, 1000=very bottom).

        GOOD example: "The 'Learn More' link that appears directly below the 'Well Woman Check-ups' heading and above the 'Platinum Health Screening' heading. It is in the lower-middle of the page. There are 6 'Learn More' links on this page and this is the 4th one from the top."
        BAD example: "The Learn More button in the Well Woman section"
    """.trimIndent()

    private val overlayNavigateInstruction = """
        You are a webpage exploration agent. You execute a specific exploration direction on a webpage.

        You see a **viewport screenshot**. A dialog/overlay is open on the page, and the screenshot shows what is currently visible in the browser viewport.
        To click an element, describe it in the `target` field. A separate system will locate the exact element from your description.
        Use `scroll_element` to scroll within the overlay if content is cut off at the bottom or top.
        To dismiss the overlay, click the close button (X) or click on the dimmed/empty space outside the overlay.

        ## YOUR ROLE
        A separate planning agent decides WHICH directions to explore and WHEN to stop the search.
        Your job is to execute the CURRENT DIRECTION given to you in the prompt.
        Focus on thoroughly exploring that one direction.

        ## How to respond
        1. **pageState**: Report ALL dynamic UI states based ONLY on what you SEE (which tab is highlighted, which sections are expanded/collapsed, which toggles are on/off). Carry forward previous entries for off-screen elements. CRITICAL: Do NOT let the query influence this — report what is ACTUALLY there.
        2. **observation**: Describe the overlay content and any changes from the last action. Note if content appears cut off (more content available via scrolling).
        3. **decision**: `continue_exploring` if there are actions to execute for the current direction. `exploration_finished` if the direction is fully explored (relevant content is visible on screen, or confirmed irrelevant).
        4. **actions** (when continuing): The actions to execute. Use `scroll_element` for scrolling within the overlay.

        ## TARGET DESCRIPTION FORMAT (CRITICAL)
        Your `target` description must be EXTREMELY specific and unambiguous. Always include ALL of the following:
        1. The element's exact visible text (in quotes)
        2. The section heading DIRECTLY above the element (in quotes)
        3. What text or element is DIRECTLY below it
        4. Its vertical position on the page: "in the top quarter / upper-middle / center / lower-middle / bottom quarter"
        5. If there are other elements with the same text on the page, state how many you see and which one you mean

        Also provide `roughY`: your estimate of the element's vertical position as 0-1000 (0=very top of page, 500=middle, 1000=very bottom).

        GOOD example: "The 'Learn More' link that appears directly below the 'Well Woman Check-ups' heading and above the 'Platinum Health Screening' heading. It is in the lower-middle of the page. There are 6 'Learn More' links on this page and this is the 4th one from the top."
        BAD example: "The Learn More button in the Well Woman section"
    """.trimIndent()

    // ── Serializable response types ─────────────────────────────────────

    @Serializable
    private data class ClickParams(val target: String? = null, val roughY: Int? = null)

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
    private data class NavigateResponse(
        val pageState: List<String>? = null,
        val observation: String? = null,
        val decision: String? = null,
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
        logger.debug("Navigate: pageState={}", pageState)

        val actions = (response.actions ?: emptyList()).mapNotNull { action ->
            try {
                toNavigationAction(action)
            } catch (e: IllegalArgumentException) {
                logger.warn("Skipping malformed action '{}': {}", action.action, e.message)
                null
            }
        }

        val decision = response.decision ?: "continue_exploring"

        logger.debug(
            "Navigate: decision={} actions=[{}] | pageState={}",
            decision,
            actions.joinToString(", ") { it::class.simpleName ?: "?" },
            pageState
        )

        return FullPageNavigationOutput(
            actions = actions,
            pageState = pageState,
            observation = response.observation,
            decision = decision,
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

        appendLine()
        appendLine("QUERY: ${input.query}")

        if (!input.directionOverrideHint.isNullOrBlank()) {
            appendLine()
            appendLine("CURRENT DIRECTION: ${input.directionOverrideHint}")
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
                val target = checkNotNull(p.target) { "click must have target description" }
                NavigationAction.Click(
                    target = target,
                    reason = reason,
                    roughY = p.roughY?.coerceIn(0, 1000)
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
            "click '${action.target}' (reason: ${action.reason})"
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
