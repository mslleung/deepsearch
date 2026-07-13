package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema

import io.deepsearch.domain.agents.ActionWithOutcome
import io.deepsearch.domain.agents.DirectionPlannerInput
import io.deepsearch.domain.agents.DirectionPlannerOutput
import io.deepsearch.domain.agents.ExplorationDirection
import io.deepsearch.domain.agents.IDirectionPlannerAgent
import io.deepsearch.domain.agents.NavigationAction
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class DirectionPlannerAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IDirectionPlannerAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

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

    private val plannerSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "explorationDirections" to Schema.builder()
                    .type("ARRAY")
                    .items(directionSchema)
                    .description("ALL exploration directions identified on the page, sorted by relevance to the query (most promising first). Carry forward previous directions and add any new ones discovered.")
                    .build(),
                "nextDirectionHint" to Schema.builder()
                    .type("STRING")
                    .nullable(true)
                    .description("A specific, actionable hint for the navigation agent: name the exact element to click and why it might contain the answer. Null if search is complete or all directions are exhausted.")
                    .build(),
                "allDirectionsExhausted" to Schema.builder()
                    .type("BOOLEAN")
                    .description("True only when EVERY direction has been explored and confirmed irrelevant or fully extracted.")
                    .build(),
                "searchComplete" to Schema.builder()
                    .type("BOOLEAN")
                    .description("True when the EXTRACTED KNOWLEDGE already contains sufficient information to answer the query. Set true when: (1) the core question is answered in the extracted content, OR (2) all directions are exhausted. Remaining unexplored directions that are unlikely to add important new information should NOT prevent completion.")
                    .build()
            )
        )
        .required(listOf("explorationDirections", "allDirectionsExhausted", "searchComplete"))
        .propertyOrdering(listOf("explorationDirections", "nextDirectionHint", "allDirectionsExhausted", "searchComplete"))
        .build()

    private val systemPrompt = """
        You are a web page EXPLORATION STRATEGIST. You see a screenshot of a webpage and a list of EXTRACTED KNOWLEDGE that has already been captured from previous exploration steps.

        Your job is to:
        1. Use the SCREENSHOT to identify exploration directions (tabs, buttons, sections to click)
        2. Use the EXTRACTED KNOWLEDGE list to decide whether the search is complete
        3. Assign the next direction for the navigation agent to explore

        ## CRITICAL: SCREENSHOT vs. EXTRACTED KNOWLEDGE
        - The SCREENSHOT is for identifying DIRECTIONS — what tabs, buttons, or sections exist on the page
        - The EXTRACTED KNOWLEDGE list is the SOLE BASIS for your searchComplete decision
        - Even if you can SEE an answer on the screenshot, it does NOT count unless it appears in the EXTRACTED KNOWLEDGE list
        - Content visible on screen has NOT been captured until it shows up in EXTRACTED KNOWLEDGE

        ## DIRECTION IDENTIFICATION
        Look at the screenshot. Identify elements that could reveal NEW content when clicked:
        - Tabs visible in the screenshot (list each one individually by its exact text)
        - Accordions, toggles, expandable sections
        - Product cards, category links, "Learn more" buttons
        - Navigation menu items that switch content on the same page

        CRITICAL: List tabs even if their names seem unrelated to the query. Content is often categorized in non-obvious ways. For example:
        - "Workers pricing" might be under "Compute & Storage", not "Workers"
        - "Health check prices" might be under individual package tabs, not a price list

        Sort directions by relevance to the query (most promising first).

        ## STATUS TRACKING
        Review the ACTION HISTORY to determine each direction's status:
        - unexplored: no action has been taken for this direction
        - exploring: an action was taken but we haven't evaluated the result yet
        - exhausted: action was taken AND outcome confirms it's done (off-page link, no relevant content, content already extracted)

        ONLY mark 'exhausted' if the ACTION HISTORY contains a click matching this direction AND the outcome shows it's irrelevant or complete.

        ## SEARCH COMPLETION EVALUATION
        After updating directions, evaluate the EXTRACTED KNOWLEDGE against the query:

        Set searchComplete=true when:
        - The EXTRACTED KNOWLEDGE contains a clear, direct answer to the query (e.g., a specific price, feature, or factual answer)
        - The core question is answered even if there are unexplored directions about unrelated topics
        - All directions have been exhausted (nothing more to try)

        Set searchComplete=false when:
        - No content has been extracted yet (EXTRACTED KNOWLEDGE is empty)
        - The extracted content does NOT answer the query
        - There are promising unexplored directions that could contain the answer

        CRITICAL: Do NOT keep exploring just because unexplored directions exist. If the answer is already in the EXTRACTED KNOWLEDGE, stop immediately. Efficiency matters.

        ## NEXT DIRECTION HINT
        If searchComplete=false, pick the most promising unexplored direction and write a specific, actionable hint:
        - Name the exact element to click
        - Explain WHY it might contain the answer
        - Example: "Click the 'Compute & Storage' tab — Workers is a compute service, so its pricing may appear in this category."

        If searchComplete=true, set nextDirectionHint to null.

        ## RULES
        - NEVER set allDirectionsExhausted=true while ANY direction has status 'unexplored'
        - Carry forward ALL previous directions — do NOT drop any
        - Add new directions discovered from the screenshot
    """.trimIndent()

    @Serializable
    private data class DirectionResponse(
        val direction: String,
        val status: String? = null
    )

    @Serializable
    private data class PlannerResponse(
        val explorationDirections: List<DirectionResponse>? = null,
        val nextDirectionHint: String? = null,
        val allDirectionsExhausted: Boolean? = null,
        val searchComplete: Boolean? = null
    )

    override suspend fun generate(input: DirectionPlannerInput): DirectionPlannerOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val prompt = buildPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<PlannerResponse>(this@DirectionPlannerAgentGenAiImpl::class.simpleName!! + ".plan") {
                val contentParts = listOf(
                    Part.fromText("WEBPAGE SCREENSHOT:"),
                    Part.fromBytes(input.screenshot.bytes, input.screenshot.mimeType.value),
                    Part.fromText(prompt)
                )
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(*contentParts.toTypedArray())),
                    GenerateContentConfig.builder()
                        .temperature(0.5f)
                        .responseSchema(plannerSchema)
                        .responseMimeType("application/json")
                        .systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
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

        val directions = (response.explorationDirections ?: emptyList()).map { d ->
            ExplorationDirection(
                direction = d.direction,
                status = d.status ?: "unexplored"
            )
        }

        val allExhausted = response.allDirectionsExhausted ?: false
        val unexploredCount = directions.count { it.status == "unexplored" }
        val exhaustedCount = directions.count { it.status == "exhausted" }

        val searchComplete = response.searchComplete ?: false

        logger.debug(
            "Planner: directions={} (unexplored={}, exhausted={}) | allExhausted={} | searchComplete={} | hint={}",
            directions.size, unexploredCount, exhaustedCount, allExhausted, searchComplete,
            response.nextDirectionHint?.take(80)
        )

        return DirectionPlannerOutput(
            explorationDirections = directions,
            nextDirectionHint = response.nextDirectionHint,
            allDirectionsExhausted = allExhausted && unexploredCount == 0,
            searchComplete = searchComplete || (allExhausted && unexploredCount == 0),
            tokenUsage = tokenUsage
        )
    }

    private fun buildPrompt(input: DirectionPlannerInput): String = buildString {
        appendLine("QUERY: ${input.query}")
        appendLine("ITERATION: ${input.currentIteration} / ${input.maxIterations}")

        if (!input.interactiveElementsHint.isNullOrBlank()) {
            appendLine()
            appendLine("INTERACTIVE ELEMENTS ON PAGE:")
            appendLine(input.interactiveElementsHint)
        }

        if (input.explorationDirections.isNotEmpty()) {
            appendLine()
            appendLine("CURRENT EXPLORATION DIRECTIONS:")
            input.explorationDirections.forEachIndexed { idx, d ->
                appendLine("  [${d.status.uppercase()}] D${idx + 1}. ${d.direction}")
            }
        }

        if (input.extractedContent.isNotEmpty()) {
            appendLine()
            appendLine("EXTRACTED KNOWLEDGE (already captured):")
            input.extractedContent.forEach { ec ->
                appendLine("  [${ec.description}]: ${ec.text.take(200)}")
            }
        }

        if (input.previousActions.isNotEmpty()) {
            appendLine()
            appendLine("ACTION HISTORY:")
            input.previousActions.forEachIndexed { idx, a ->
                val actionDesc = when (a.action) {
                    is NavigationAction.Click -> "click '${a.action.target}'"
                    is NavigationAction.Type -> "type '${a.action.text}'"
                    is NavigationAction.ScrollAt -> "scroll ${a.action.scrollDirection}"
                    is NavigationAction.ExplorationFinished -> "exploration_finished"
                    is NavigationAction.GiveUp -> "give_up"
                }
                val outcome = a.outcome?.let { " → $it" } ?: ""
                appendLine("  ${idx + 1}. $actionDesc$outcome")
            }
        }
    }
}
