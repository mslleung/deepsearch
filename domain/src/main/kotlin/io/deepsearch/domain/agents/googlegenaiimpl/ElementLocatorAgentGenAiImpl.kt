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
        val label: Int = -1,
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
                "label" to Schema.builder().type("INTEGER")
                    .description("The numbered label on the screenshot that matches the target element. Set to -1 if the element cannot be found.")
                    .build(),
                "confidence" to Schema.builder().type("STRING")
                    .description("How confident you are: 'high' if the match is unambiguous, 'low' if multiple candidates exist or the match is uncertain.")
                    .enum_(listOf("high", "low"))
                    .build()
            )
        )
        .required(listOf("index", "label", "confidence"))
        .build()

    private val locatorSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "resolved" to Schema.builder()
                    .type("ARRAY")
                    .items(resolvedTargetSchema)
                    .description("One entry per target, with the label number of the matching element on the annotated screenshot.")
                    .build()
            )
        )
        .required(listOf("resolved"))
        .build()

    private val systemInstruction = """
        You are a visual element locator. You receive:
        1. An ANNOTATED screenshot of a webpage — interactive elements are highlighted with colored bounding boxes and numbered labels (e.g., [0], [1], [12])
        2. One or more TARGET DESCRIPTIONS of elements to find on that page

        For each target, identify which numbered label on the screenshot matches the described element.
        Return the label number of the matching element.

        CRITICAL: Multiple elements often have IDENTICAL visible text (e.g., many "Learn More" buttons).
        Do NOT pick the first match. For each target:
        1. Read the FULL target description carefully — it specifies section headings, position, and surrounding context
        2. Scan the ENTIRE screenshot for ALL labeled elements with matching text
        3. Use the spatial and contextual cues in the description to select the CORRECT labeled element
        4. Return the label number of ONLY that specific element
        5. If unsure between candidates, set confidence to "low"

        If no labeled element on the screenshot matches the target description, set label to -1.
    """.trimIndent()

    override suspend fun generate(input: ElementLocatorInput): ElementLocatorOutput {
        val modelId = ModelIds.GEMINI_3_5_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val prompt = buildPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<LocatorResponse>(this@ElementLocatorAgentGenAiImpl::class.simpleName!! + ".locate") {
                val contentParts = listOf(
                    Part.fromText("ANNOTATED SCREENSHOT (elements are highlighted with numbered labels):"),
                    Part.fromBytes(input.screenshot.bytes, input.screenshot.mimeType.value),
                    Part.fromText(prompt)
                )
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(*contentParts.toTypedArray())),
                    GenerateContentConfig.builder()
                        .responseSchema(locatorSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.MINIMAL).build())
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
            val label = r.label
            val element = if (label >= 0) input.elementIndex[label] else null
            if (element != null) {
                ResolvedTarget(
                    index = r.index,
                    elementLabel = label,
                    confidence = r.confidence,
                    centerXNorm = element.centerX,
                    centerYNorm = element.centerY
                )
            } else {
                ResolvedTarget(
                    index = r.index,
                    elementLabel = if (label >= 0) label else null,
                    confidence = r.confidence
                )
            }
        }

        logger.debug(
            "Element locator resolved {} targets: {}",
            resolved.size,
            resolved.joinToString { "#${it.index}→label=${it.elementLabel},center=(${it.centerXNorm},${it.centerYNorm})(${it.confidence})" }
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
        appendLine("For each target, find the described element on the annotated screenshot and return the label number of the matching element.")
    }
}
