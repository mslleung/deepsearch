package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IMarkdownFormattingAgent
import io.deepsearch.domain.agents.MarkdownFormattingInput
import io.deepsearch.domain.agents.MarkdownFormattingOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Google GenAI implementation of the markdown formatting agent.
 * 
 * Formats raw extracted webpage content into a well-structured, standalone markdown document.
 */
class MarkdownFormattingAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IMarkdownFormattingAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Formatted markdown document from raw webpage content")
        .properties(
            mapOf(
                "markdown" to Schema.builder()
                    .type("STRING")
                    .description("Well-structured markdown document formatted from the raw extracted content")
                    .build()
            )
        )
        .required(listOf("markdown"))
        .build()

    @Serializable
    private data class MarkdownFormattingResponse(
        val markdown: String
    )

    private val systemInstruction = """
        You are a markdown formatting expert. Your task is to convert raw extracted webpage content 
        into a well-structured, standalone markdown document.

        Input:
        - Raw text extracted from a webpage, they may lack structure
            - Icons are already converted to text
            - Images are converted to text already in markdown format: ![description](#img-N) where N is the image number
            - Tables are already converted to markdown format

        Instructions:
        - Stick as close to the raw text as possible in terms of content, focus on enriching format
        - Structure the content with appropriate heading hierarchy (# for main title, ## for sections, etc.)
        - Icons should be interpreted and included as necessary
        - Preserve image/tables references at a reasonable position in the markdown

        Expected output format:
        {
          "markdown": string
        }
    """.trimIndent()

    override suspend fun generate(input: MarkdownFormattingInput): MarkdownFormattingOutput {
        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        // Build the user prompt with just raw text (no metadata)
        val userPrompt = buildUserPrompt(input)

        logger.debug("Formatting markdown for URL: {}, raw text length: {} chars", input.url, input.rawText.length)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<MarkdownFormattingResponse>(this@MarkdownFormattingAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(Part.fromText(userPrompt))),
                    GenerateContentConfig.builder()
                        .temperature(0.1F) // Low temperature for consistent formatting
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingBudget(0)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                        .build()
                )

                result.checkFinishReason()

                // Extract token usage
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

        // Build final markdown with metadata header
        val finalMarkdown = buildFinalMarkdown(input, response.markdown.trim())

        logger.debug("Markdown formatting complete, output length: {} chars", finalMarkdown.length)

        return MarkdownFormattingOutput(
            markdown = finalMarkdown,
            tokenUsage = tokenUsage
        )
    }

    /**
     * Builds the user prompt containing just the raw text for LLM formatting.
     */
    private fun buildUserPrompt(input: MarkdownFormattingInput): String = buildString {
        if (!input.popupText.isNullOrBlank()) {
            appendLine("=== POPUP CONTENT ===")
            appendLine(input.popupText)
            appendLine()
        }

        appendLine("=== RAW EXTRACTED CONTENT ===")
        appendLine(input.rawText)
    }

    /**
     * Builds the final markdown document with metadata header and LLM-formatted content.
     */
    private fun buildFinalMarkdown(input: MarkdownFormattingInput, formattedContent: String): String = buildString {
        appendLine("URL: ${input.url}")
        if (!input.title.isNullOrBlank()) {
            appendLine("Title: ${input.title}")
        }
        if (!input.description.isNullOrBlank()) {
            appendLine("Description: ${input.description}")
        }
        appendLine()
        append(formattedContent)
    }
}
