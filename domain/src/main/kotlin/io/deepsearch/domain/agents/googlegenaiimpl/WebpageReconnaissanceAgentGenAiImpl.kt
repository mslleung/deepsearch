package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel

import io.deepsearch.domain.agents.IWebpageReconnaissanceAgent
import io.deepsearch.domain.agents.WebpageReconnaissanceInput
import io.deepsearch.domain.agents.WebpageReconnaissanceOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

class WebpageReconnaissanceAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IWebpageReconnaissanceAgent {

    private val logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "pageStructure" to Schema.builder()
                    .type("STRING")
                    .description("2-3 sentence description of the page layout: major sections, tables, navigation elements, and overall content organization.")
                    .build(),
                "scrollTargetText" to Schema.builder()
                    .type("STRING")
                    .description("A short text snippet (verbatim from the page) near the most query-relevant section. Used as a Ctrl+F anchor to scroll there. Empty string if the relevant content is near the top or no clear anchor exists.")
                    .build(),
                "scrollTargetOccurrence" to Schema.builder()
                    .type("INTEGER")
                    .description("Which occurrence of scrollTargetText to scroll to (1-based). If the text appears multiple times on the page, specify which one is closest to the relevant content. Defaults to 1.")
                    .build()
            )
        )
        .required(listOf("pageStructure", "scrollTargetText", "scrollTargetOccurrence"))
        .build()

    private val systemInstruction = """
        You are a webpage reconnaissance agent. You receive the EXTRACTED TEXT CONTENT of a
        webpage and a QUERY. Your job is to analyze the page structure and identify where to
        scroll — you do NOT extract the answer yourself.

        Your tasks:
        1. DESCRIBE the page structure: what sections exist, how the content is organized,
           and the general layout flow from top to bottom.
        2. IDENTIFY a short text snippet (copied verbatim from the page text) that appears
           near the section most relevant to the query. This will be used as a Ctrl+F anchor
           to scroll the browser viewport there. Choose a distinctive phrase that won't match
           in unrelated sections. Return an empty string if the relevant content is near the
           top of the page.
        3. If the chosen text snippet appears MULTIPLE TIMES on the page, set
           scrollTargetOccurrence to the 1-based index of the correct match (e.g., 2 for
           the second occurrence). Count occurrences by scanning the page text from top to
           bottom. If the snippet is unique or you want the first match, use 1.

        IMPORTANT: Focus on STRUCTURE, not on reading specific values.
        A separate agent will read the precise content from the viewport.
    """.trimIndent()

    @Serializable
    private data class ReconResponse(
        val pageStructure: String,
        val scrollTargetText: String,
        val scrollTargetOccurrence: Int
    )

    override suspend fun generate(input: WebpageReconnaissanceInput): WebpageReconnaissanceOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val userPrompt = buildString {
            appendLine("PAGE_TEXT:")
            appendLine(input.pageText)
            appendLine()
            appendLine("QUERY: ${input.query}")
        }

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<ReconResponse>(this@WebpageReconnaissanceAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(Part.fromText(userPrompt))),
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

        val scrollTarget = response.scrollTargetText.ifBlank { null }

        logger.info(
            "Reconnaissance complete: scrollTarget='{}' (occurrence {}), tokens={}",
            scrollTarget, response.scrollTargetOccurrence, tokenUsage.totalTokens
        )

        return WebpageReconnaissanceOutput(
            pageStructure = response.pageStructure,
            scrollTargetText = scrollTarget,
            scrollTargetOccurrence = response.scrollTargetOccurrence.coerceAtLeast(1),
            tokenUsage = tokenUsage
        )
    }
}
