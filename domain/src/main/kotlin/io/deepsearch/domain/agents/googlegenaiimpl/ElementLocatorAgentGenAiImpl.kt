package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.ElementLocatorInput
import io.deepsearch.domain.agents.ElementLocatorOutput
import io.deepsearch.domain.agents.IElementLocatorAgent
import io.deepsearch.domain.agents.ResolvedTarget
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ElementLocatorAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IElementLocatorAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Serializable
    private data class ResolvedTargetResponse(
        val index: Int,
        val box_2d: List<Int>? = null,
        val confidence: String? = null
    )

    @Serializable
    private data class LocatorResponse(
        val resolved: List<ResolvedTargetResponse>
    )

    private val resolvedTargetSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "index" to Schema.builder().type("INTEGER")
                    .description("The index of the target being resolved (from the input).")
                    .build(),
                "box_2d" to Schema.builder().type("ARRAY")
                    .items(Schema.builder().type("INTEGER").build())
                    .description("Bounding box [ymin, xmin, ymax, xmax] normalized to 0-1000 around the target element. Set to [0, 0, 0, 0] if the element cannot be found.")
                    .build(),
                "confidence" to Schema.builder().type("STRING")
                    .description("How confident you are: 'high' if the match is unambiguous, 'low' if multiple candidates exist or the match is uncertain.")
                    .enum_(listOf("high", "low"))
                    .build()
            )
        )
        .required(listOf("index", "box_2d", "confidence"))
        .build()

    private val locatorSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "resolved" to Schema.builder()
                    .type("ARRAY")
                    .items(resolvedTargetSchema)
                    .description("One entry per target, with a bounding box around the matching element on the screenshot.")
                    .build()
            )
        )
        .required(listOf("resolved"))
        .build()

    private val systemInstruction = """
        You are a visual element locator. You receive:
        1. A screenshot of a webpage
        2. One or more TARGET DESCRIPTIONS of elements to find on that page

        For each target, locate the described element on the screenshot and return a tight bounding box around it.
        Return box_2d as [ymin, xmin, ymax, xmax] normalized to 0-1000. Origin (0,0) is top-left.

        CRITICAL: Multiple elements often have IDENTICAL visible text (e.g., many "Learn More" buttons).
        Do NOT pick the first match. For each target:
        1. Read the FULL target description carefully — it specifies section headings, position, and surrounding context
        2. Scan the ENTIRE screenshot for ALL elements with matching text
        3. Use the spatial and contextual cues in the description to select the CORRECT one
        4. Draw a tight bounding box around ONLY that specific element (not the entire section)
        5. If unsure between candidates, set confidence to "low"

        The bounding box should tightly wrap the clickable element itself (button, link, text), not the surrounding section.
        If no element on the screenshot matches the target description, set box_2d to [0, 0, 0, 0].
    """.trimIndent()

    override suspend fun generate(input: ElementLocatorInput): ElementLocatorOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val prompt = buildPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<LocatorResponse>(this@ElementLocatorAgentGenAiImpl::class.simpleName!! + ".locate") {
                val contentParts = listOf(
                    Part.fromText("SCREENSHOT:"),
                    Part.fromBytes(input.screenshot.bytes, input.screenshot.mimeType.value),
                    Part.fromText(prompt)
                )

                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(*contentParts.toTypedArray())),
                    GenerateContentConfig.builder()
                        .temperature(1.0F)
                        .responseSchema(locatorSchema)
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

        val resolved = response.resolved.map { r ->
            val box = r.box_2d
            val isValid = box != null && box.size >= 4 && !(box[0] == 0 && box[1] == 0 && box[2] == 0 && box[3] == 0)
            if (isValid) {
                val ymin = box[0].coerceIn(0, 1000)
                val xmin = box[1].coerceIn(0, 1000)
                val ymax = box[2].coerceIn(0, 1000)
                val xmax = box[3].coerceIn(0, 1000)
                val centerXNorm = (xmin + xmax) / 2
                val centerYNorm = (ymin + ymax) / 2
                ResolvedTarget(
                    index = r.index,
                    confidence = r.confidence,
                    centerXNorm = centerXNorm,
                    centerYNorm = centerYNorm
                )
            } else {
                ResolvedTarget(
                    index = r.index,
                    confidence = r.confidence
                )
            }
        }

        logger.debug(
            "Element locator resolved {} targets: {}",
            resolved.size,
            resolved.joinToString { "#${it.index}→center=(${it.centerXNorm},${it.centerYNorm})(${it.confidence})" }
        )

        return ElementLocatorOutput(
            resolved = resolved,
            tokenUsage = tokenUsage
        )
    }

    private fun buildPrompt(input: ElementLocatorInput): String = buildString {
        appendLine("PAGE: ${input.pageUrl}")
        appendLine()
        appendLine("TARGETS TO LOCATE:")
        input.targets.forEach { target ->
            appendLine("  [${target.index}] ${target.target}")
            if (target.reason.isNotBlank()) {
                appendLine("       Reason: ${target.reason}")
            }
        }
        appendLine()
        appendLine("For each target, find the described element on the screenshot and return a tight bounding box [ymin, xmin, ymax, xmax] normalized to 0-1000.")
    }
}
