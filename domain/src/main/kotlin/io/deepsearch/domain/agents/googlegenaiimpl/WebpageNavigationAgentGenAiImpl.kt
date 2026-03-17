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
                "observation" to Schema.builder()
                    .type("STRING")
                    .description("What you see on the current screen, what happened from your last action, and your plan. This is your memory across turns.")
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
                    .description("Answer synthesized from all findings (decision=exploration_finished only). Null if no relevant findings were discovered.")
                    .nullable(true)
                    .build()
            )
        )
        .required(listOf("observation", "questionsState", "generalFindings", "decision", "reason"))
        .propertyOrdering(listOf("observation", "questionsState", "generalFindings", "captureRegions", "decision", "reason", "actions", "answer"))
        .build()

    private val systemInstruction = """
        You are a webpage exploration agent examining a single page to answer a query.

        ## Input
        The input is the accumulated progress of all previous exploration iterations on the current webpage:
        - Screenshot of current viewport
        - iteration budget
        - input query
        - open questions derived from the query and corresponding the findings so far
        - general findings that may be useful
        - previous exploration iterations
        
        ## Strategy
        1. Extract relevant findings from the current viewport screenshot. Aim to capture all relevant content with no omission, keep keywords intact. If keywords are in another language, keep them in the original language instead of transliterate.
        2. Explore the page for more information. Approach this just like a real human doing research. Look exhaustively for all relevant information on the page as efficiently as possible.
            - Control-F to jump to text
            - Scroll around the webpage
            - Click around the page to reveal accordions, interact with toggles, inputting text etc.
            - Any other actions that's worth trying
        3. Since only the viewport is visible to you. If the captured content is near the viewport edge, scroll in that direction to make sure information is not cut off by the screen.
        4. If a click led to a navigation to another screen, it would be recorded and processed separately, you can just continue exploring the current page.
        5. Capture regions that are useful as a reference as captureRegions.

        ## Decision & Actions
        **continue_exploring**: Provide ALL exploration actions you think will yield information. Be eager — include every action worth trying.
        - **type_text**: Type into input field at (x,y). Highest priority — triggers search/filter.
        - **click**: Click element at (x,y) in 0-1000 scale. Include all clickable targets (buttons, tabs, accordions, links).
        - **find_on_page**: Search keywords with stemming. Auto-scrolls to best match.
        - **scroll_to_text**: Jump to specific text after find_on_page.
        - **scroll_page**: Scroll viewport UP/DOWN/LEFT/RIGHT. Always try to scroll as much as possible, without cutting text in the middle by the viewport boundaries.
        - **scroll_element**: Scroll container at (x,y).
        - **peek_full_page**: Full-page overview. Last resort.

        **exploration_finished**: Stop exploring and return. Provide an answer synthesized from all findings, or null if no relevant findings were discovered.
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
    private data class QuestionStateResponse(
        val question: String,
        val status: String? = null,
        val findings: List<String>? = null
    )

    @Serializable
    private data class NavigationResponse(
        val observation: String? = null,
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
            "exploration_finished" -> {
                listOf(NavigationAction.ExplorationFinished(
                    answer = response.answer?.takeIf { it.isNotBlank() }
                ))
            }
            else -> {
                (response.actions ?: emptyList()).map { toNavigationAction(it) }
            }
        }

        val questionsState = (response.questionsState ?: emptyList()).map { qs ->
            TrackedQuestion(
                question = qs.question,
                resolved = qs.status?.lowercase() == "resolved",
                findings = qs.findings ?: emptyList()
            )
        }
        val generalFindings = response.generalFindings ?: emptyList()

        val openCount = questionsState.count { !it.resolved }
        val resolvedCount = questionsState.count { it.resolved }
        val totalFindings = questionsState.sumOf { it.findings.size } + generalFindings.size
        logger.debug(
            "Navigation: decision={} actions=[{}] | questions={} (open={}, resolved={}) | findings={}",
            response.decision,
            actions.joinToString(", ") { it::class.simpleName ?: "?" },
            questionsState.size, openCount, resolvedCount, totalFindings
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
            questionsState = questionsState,
            generalFindings = generalFindings,
            observation = response.observation,
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
                NavigationAction.Click(
                    x = p.x.coerceIn(0, 1000),
                    y = p.y.coerceIn(0, 1000),
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
                occurrence = 1,
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
