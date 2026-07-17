package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema

import io.deepsearch.domain.agents.ActionWithOutcome
import io.deepsearch.domain.agents.ContinuationAssessment
import io.deepsearch.domain.agents.DimensionAssessment
import io.deepsearch.domain.agents.ExploredAction
import io.deepsearch.domain.agents.ExplorationDirection
import io.deepsearch.domain.agents.IFullPageNavigationAgent
import io.deepsearch.domain.agents.IterationProgress
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
                    .build(),
                "priority" to Schema.builder().type("STRING")
                    .enum_(listOf("high", "medium", "low"))
                    .description(
                        "high: label/section directly mentions query terms or synonyms, or the HIDDEN CONTENT signal shows matches there. " +
                        "medium: could plausibly contain the answer. " +
                        "low: unlikely (generic navigation, unrelated topic). Unexplored LOW directions do NOT block completion."
                    )
                    .build()
            )
        )
        .required(listOf("direction", "status", "priority"))
        .propertyOrdering(listOf("direction", "status", "priority"))
        .build()

    // ── Continuation assessment schema ─────────────────────────────────

    private val dimensionAssessmentSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(mapOf(
            "satisfied" to Schema.builder().type("BOOLEAN")
                .description("Whether this dimension is met.").build(),
            "rationale" to Schema.builder().type("STRING")
                .description("One-sentence justification grounded in observable evidence.").build()
        ))
        .required(listOf("satisfied", "rationale"))
        .build()

    private val continuationAssessmentSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(mapOf(
            "answerFound" to dimensionAssessmentSchema,
            "pageRelevant" to dimensionAssessmentSchema,
            "unexploredPotential" to dimensionAssessmentSchema,
            "recentProgress" to dimensionAssessmentSchema
        ))
        .required(listOf("answerFound", "pageRelevant", "unexploredPotential", "recentProgress"))
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
                    .description("The specific direction your actions are for. Null when the search is stopping.")
                    .build(),
                "continuationAssessment" to continuationAssessmentSchema,
                "queryKeywords" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description(
                        "3-8 short, distinctive search terms whose presence on the page would indicate the query's answer exists. " +
                        "Include synonyms and alternate phrasings (e.g. for telemedicine: 'telemedicine', 'virtual consultation', 'video consultation'). " +
                        "Prefer single words or tight 2-word phrases. Avoid generic words ('price', 'plan') and the site's own brand/company name — they match everywhere and mask the absence signal. " +
                        "These power the HIDDEN CONTENT scan on the next iteration. Provide on every response; refine as understanding improves."
                    )
                    .build(),
                "actions" to Schema.builder()
                    .type("ARRAY")
                    .items(actionSchema)
                    .description("Actions to execute in order. Include ALL actions that advance the current direction. Empty array when the search is stopping. When switching to a new direction, include the first actions for the new direction immediately.")
                    .nullable(true)
                    .build()
            )
        )
        .required(listOf("pageState", "observation", "explorationDirections", "continuationAssessment", "queryKeywords"))
        .propertyOrdering(listOf("pageState", "observation", "explorationDirections", "currentDirection", "continuationAssessment", "queryKeywords", "actions"))
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

        List directions that could PLAUSIBLY contain information related to the query based on your understanding of the webpage.
        Sort directions by relevance to the query (most promising first).

        **Direction Priority:**
        Assign each direction a priority:
        - high: the label/section directly mentions query terms or synonyms, OR the HIDDEN CONTENT signal shows matches in that area
        - medium: could plausibly contain the answer
        - low: unlikely but not impossible (generic navigation menus, unrelated topics)
        Explore in priority order. NEVER explore a low direction unless the HIDDEN CONTENT signal indicates matches there.
        Unexplored LOW directions do NOT block completion.

        **HIDDEN CONTENT (DOM evidence of collapsed/hidden content matching query terms):**
        You may receive a HIDDEN CONTENT list — keywords found in DOM elements that are NOT visible on screen
        (behind collapsed accordions, inactive tabs, etc.). The context snippet hints at which section contains them.
        - Prioritize revealing hidden content by clicking the relevant accordion/tab.
        - If no HIDDEN CONTENT section is shown, all query-relevant text is either already visible or absent from the DOM.

        **Direction Status Tracking:**
        Review the ACTION HISTORY to determine each direction's status:
        - unexplored: no action has been taken for this direction
        - exploring: actions are being taken for this direction
        - exhausted: action was taken AND outcome confirms it's done (off-page link, no relevant content, content already extracted)

        ONLY mark 'exhausted' if the ACTION HISTORY contains actions matching this direction AND the outcome shows it's irrelevant or complete.

        ### 2. NAVIGATION EXECUTION (tactical)
        Execute actions to explore the current direction — click the right elements, scroll to reveal content.

        Be selective: when a section has multiple items (accordions, tabs, links), only click ones whose visible labels are plausibly relevant to the query. Do NOT exhaustively open every item in a section. If none of the remaining items in a section could plausibly contain the answer, mark the direction as exhausted and move on.

        When the current direction is done (content visible or confirmed irrelevant), switch to the next promising direction immediately:
        - Mark the old direction as 'exhausted'
        - Set the new direction as 'exploring'
        - Provide actions for the new direction in the same response

        ## CONTINUATION ASSESSMENT
        Assess these four dimensions BEFORE choosing actions. The search stops deterministically when answerFound is satisfied OR pageRelevant/unexploredPotential is unsatisfied. Evaluate honestly — continuing costs real money.

        ### answerFound
        - satisfied=true when the EXTRACTED KNOWLEDGE contains a clear answer to the query.
        - CLOSED-WORLD RULE: If the page presents a COMPLETE enumeration of the relevant category (all services listed, all FAQ topics shown, all packages displayed) and the queried item is NOT in the enumeration, that absence IS the answer. State it in your observation: "The page lists [enumeration] and [target] is not among them." Set satisfied=true.
        - satisfied=false when nothing extracted answers the query (including by absence).

        ### pageRelevant
        - satisfied=true if this page's content domain could plausibly contain the answer.
        - satisfied=false if the page is about a fundamentally different topic than the query asks about.

        ### unexploredPotential
        - satisfied=true ONLY if you can name a SPECIFIC unexplored element (accordion, tab, section, or HIDDEN CONTENT keyword match) that plausibly contains the answer.
        - The rationale MUST name the element. "There might be something" = NOT satisfied.
        - Off-page links do not count (out of scope for in-page search).
        - Elements already clicked (per TURNS history) do not count.
        - satisfied=false when all promising areas are explored or no element's label/signal suggests query relevance.

        ### recentProgress
        - Judge strictly from the PROGRESS LOG. Did recent iterations produce new query-relevant extractions or keyword reveals?
        - satisfied=true if the last 1-2 iterations produced gains.
        - satisfied=false if the last 2+ iterations produced zero gains.
        - If no PROGRESS LOG is shown (first iteration), set satisfied=true.

        ## CRITICAL: SCREENSHOT vs. EXTRACTED KNOWLEDGE
        - The SCREENSHOT is for identifying DIRECTIONS and deciding which elements to click
        - The EXTRACTED KNOWLEDGE list is the SOLE BASIS for answerFound
        - Even if you can SEE an answer on the screenshot, it does NOT count unless it appears in the EXTRACTED KNOWLEDGE list
        - Content visible on screen has NOT been captured until it shows up in EXTRACTED KNOWLEDGE

        ## How to respond
        1. **pageState**: Report ALL dynamic UI states based ONLY on what you SEE (which tab is highlighted, which sections are expanded/collapsed). Carry forward previous entries for off-screen elements. Do NOT let the query influence this — report what is ACTUALLY there.
        2. **observation**: Describe the page layout and any changes from the last action.
        3. **explorationDirections**: ALL directions with updated statuses. Carry forward previous directions. Add new ones discovered.
        4. **currentDirection**: Which direction your actions are for. Null when the search is stopping.
        5. **continuationAssessment**: Your 4-dimension assessment (answerFound, pageRelevant, unexploredPotential, recentProgress).
        6. **actions**: The navigation actions to execute. Empty array when the search is stopping.

        ## CLICKING ELEMENTS
        Reference elements by their numbered label [N] on the screenshot. Provide the number in `elementLabel`.
        If you cannot find a suitable labeled element for what you need to click, use -1 and describe the element in `target`.
        NEVER re-click an element listed in PREVIOUSLY EXPLORED / ALREADY EXPLORED. If all visible interactive elements have already been explored and no new content has appeared, set unexploredPotential.satisfied = false.

        ## RULES
        - Carry forward ALL previous directions — do NOT drop any
        - When switching directions, include the first actions for the new direction immediately
        - ALWAYS provide queryKeywords (refined each turn)
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
        List directions that could PLAUSIBLY contain information related to the query based on your understanding of the webpage.
        Assign each direction a priority (high/medium/low); unexplored LOW directions do NOT block completion.

        You may receive a HIDDEN CONTENT list — keywords found in DOM elements that are NOT visible on screen
        (behind collapsed accordions, inactive tabs, etc.). Prioritize revealing them by clicking the relevant element.

        ### 2. NAVIGATION EXECUTION (tactical)
        Execute actions to explore the current direction within the overlay. Be selective: only click items whose labels are plausibly relevant to the query. Do NOT exhaustively open every item.
        When the current direction is done, switch to the next promising direction immediately.

        ## CONTINUATION ASSESSMENT
        Assess these four dimensions BEFORE choosing actions. The search stops deterministically when answerFound is satisfied OR pageRelevant/unexploredPotential is unsatisfied. Evaluate honestly — continuing costs real money.

        ### answerFound
        - satisfied=true when the EXTRACTED KNOWLEDGE contains a clear answer to the query.
        - CLOSED-WORLD RULE: If the page presents a COMPLETE enumeration of the relevant category and the queried item is NOT in it, that absence IS the answer. Set satisfied=true.
        - satisfied=false when nothing extracted answers the query.

        ### pageRelevant
        - satisfied=true if this page's content domain could plausibly contain the answer.
        - satisfied=false if the page is about a fundamentally different topic.

        ### unexploredPotential
        - satisfied=true ONLY if you can name a SPECIFIC unexplored element that plausibly contains the answer. The rationale MUST name it.
        - Off-page links and already-clicked elements do not count.
        - satisfied=false when all promising areas are explored or no element suggests query relevance.

        ### recentProgress
        - Judge strictly from the PROGRESS LOG.
        - satisfied=true if the last 1-2 iterations produced gains.
        - satisfied=false if the last 2+ iterations produced zero gains.
        - If no PROGRESS LOG is shown (first iteration), set satisfied=true.

        CRITICAL: The EXTRACTED KNOWLEDGE list is the SOLE BASIS for answerFound. Content visible on screen has NOT been captured until it appears in EXTRACTED KNOWLEDGE.

        ## How to respond
        1. **pageState**: Report ALL dynamic UI states based ONLY on what you SEE. Carry forward previous entries. Do NOT let the query influence this.
        2. **observation**: Describe the overlay content and any changes. Note if content appears cut off.
        3. **explorationDirections**: ALL directions with updated statuses.
        4. **currentDirection**: Which direction your actions are for. Null when the search is stopping.
        5. **continuationAssessment**: Your 4-dimension assessment.
        6. **actions**: Use `scroll_element` for scrolling within the overlay. Empty when the search is stopping.

        ## CLICKING ELEMENTS
        Reference elements by their numbered label [N] on the screenshot. Provide the number in `elementLabel`.
        If you cannot find a suitable labeled element for what you need to click, use -1 and describe the element in `target`.
        NEVER re-click an element listed in PREVIOUSLY EXPLORED / ALREADY EXPLORED. If all visible interactive elements have already been explored and no new content has appeared, set unexploredPotential.satisfied = false.

        ## RULES
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
        val status: String? = null,
        val priority: String? = null
    )

    @Serializable
    private data class DimensionAssessmentResponse(
        val satisfied: Boolean = false,
        val rationale: String = ""
    )

    @Serializable
    private data class ContinuationAssessmentResponse(
        val answerFound: DimensionAssessmentResponse = DimensionAssessmentResponse(),
        val pageRelevant: DimensionAssessmentResponse = DimensionAssessmentResponse(),
        val unexploredPotential: DimensionAssessmentResponse = DimensionAssessmentResponse(),
        val recentProgress: DimensionAssessmentResponse = DimensionAssessmentResponse()
    )

    @Serializable
    private data class NavigateResponse(
        val pageState: List<String>? = null,
        val observation: String? = null,
        val explorationDirections: List<DirectionResponse>? = null,
        val currentDirection: String? = null,
        val queryKeywords: List<String>? = null,
        val continuationAssessment: ContinuationAssessmentResponse? = null,
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
                status = d.status ?: "unexplored",
                priority = when (d.priority?.lowercase()) {
                    "high", "medium", "low" -> d.priority.lowercase()
                    else -> "medium"
                }
            )
        }

        val assessmentResp = response.continuationAssessment ?: ContinuationAssessmentResponse()
        val continuationAssessment = ContinuationAssessment(
            answerFound = DimensionAssessment(assessmentResp.answerFound.satisfied, assessmentResp.answerFound.rationale),
            pageRelevant = DimensionAssessment(assessmentResp.pageRelevant.satisfied, assessmentResp.pageRelevant.rationale),
            unexploredPotential = DimensionAssessment(assessmentResp.unexploredPotential.satisfied, assessmentResp.unexploredPotential.rationale),
            recentProgress = DimensionAssessment(assessmentResp.recentProgress.satisfied, assessmentResp.recentProgress.rationale)
        )

        val searchComplete = continuationAssessment.shouldStop()
        val allDirectionsExhausted = !continuationAssessment.unexploredPotential.satisfied

        val actions = (response.actions ?: emptyList()).mapNotNull { action ->
            try {
                toNavigationAction(action)
            } catch (e: IllegalArgumentException) {
                logger.warn("Skipping malformed action '{}': {}", action.action, e.message)
                null
            }
        }

        logger.debug(
            "Navigate: directions={} | searchComplete={} (answer={}, relevant={}, potential={}, progress={}) | currentDir={} | actions=[{}]",
            directions.size, searchComplete,
            continuationAssessment.answerFound.satisfied,
            continuationAssessment.pageRelevant.satisfied,
            continuationAssessment.unexploredPotential.satisfied,
            continuationAssessment.recentProgress.satisfied,
            response.currentDirection?.take(60),
            actions.joinToString(", ") { it::class.simpleName ?: "?" }
        )

        return FullPageNavigationOutput(
            actions = actions,
            pageState = pageState,
            observation = response.observation,
            explorationDirections = directions,
            currentDirection = response.currentDirection,
            searchComplete = searchComplete,
            allDirectionsExhausted = allDirectionsExhausted,
            queryKeywords = (response.queryKeywords ?: emptyList()).map { it.trim() }.filter { it.isNotBlank() },
            continuationAssessment = continuationAssessment,
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

        val hiddenEntries = input.keywordScan.filter { it.totalCount > it.visibleCount }
        if (hiddenEntries.isNotEmpty()) {
            appendLine()
            appendLine("HIDDEN CONTENT (keywords found in collapsed/hidden DOM elements — click to reveal):")
            hiddenEntries.forEach { e ->
                val hidden = e.totalCount - e.visibleCount
                val context = e.firstContext?.let { " | near: \"$it\"" } ?: ""
                appendLine("  - \"${e.keyword}\": $hidden hidden match(es)$context")
            }
        }

        if (input.explorationDirections.isNotEmpty()) {
            appendLine()
            appendLine("CURRENT EXPLORATION DIRECTIONS:")
            input.explorationDirections.forEachIndexed { idx, d ->
                appendLine("  [${d.status.uppercase()}|${d.priority.uppercase()}] D${idx + 1}. ${d.direction}")
            }
        }

        if (input.extractedContent.isNotEmpty()) {
            appendLine()
            appendLine("EXTRACTED KNOWLEDGE (already captured — basis for answerFound assessment):")
            input.extractedContent.forEach { ec ->
                appendLine("  [${ec.description}]: ${ec.text.take(200)}")
            }
        }

        if (input.progressLog.isNotEmpty()) {
            appendLine()
            appendLine("PROGRESS LOG (host-measured, most recent last):")
            input.progressLog.forEach { p ->
                appendLine("  - iter ${p.iteration}: ${p.newExtractions} new extraction(s), ${p.newKeywordReveals} keyword reveal(s)")
            }
        }

        renderExploredHistory(input.exploredActions)

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

    private fun StringBuilder.renderExploredHistory(exploredActions: List<ExploredAction>) {
        if (exploredActions.isEmpty()) return
        val grouped = exploredActions.groupBy { it.direction ?: "(no direction)" }
        appendLine()
        appendLine("PREVIOUSLY EXPLORED (prior directions — do NOT re-click these targets):")
        for ((direction, actions) in grouped) {
            val targets = actions.joinToString(", ") { a ->
                val outcomeTag = when {
                    a.outcome == null -> ""
                    a.outcome.contains("off-page", ignoreCase = true) ||
                        a.outcome.contains("OFF-PAGE", ignoreCase = true) -> " (off-page)"
                    a.outcome.contains("no visible change", ignoreCase = true) ||
                        a.outcome.contains("NO visible change", ignoreCase = true) -> " (no change)"
                    a.outcome.contains("changed", ignoreCase = true) -> " (page changed)"
                    else -> ""
                }
                "${a.target}$outcomeTag"
            }
            appendLine("  - \"${direction.take(60)}\": $targets")
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
