package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import com.ibm.icu.text.BreakIterator
import io.deepsearch.domain.agents.IMarkdownFormattingAgent
import io.deepsearch.domain.agents.MarkdownFormattingInput
import io.deepsearch.domain.agents.MarkdownFormattingOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * Google GenAI implementation of the markdown formatting agent.
 * 
 * Formats raw extracted webpage content into a well-structured, standalone markdown document.
 * Uses parallel chunk processing for improved performance on large inputs.
 */
class MarkdownFormattingAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IMarkdownFormattingAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        /** Target size for each chunk in characters */
        private const val TARGET_CHUNK_SIZE = 1500
        /** Minimum size for a chunk to be processed separately */
        private const val MIN_CHUNK_SIZE = 500
        /** Threshold below which we don't bother chunking */
        private const val CHUNKING_THRESHOLD = 2000
    }

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
        - Omit useless webpage extraction noise such as cookie declaration table etc.

        Expected output format:
        {
          "markdown": string
        }
    """.trimIndent()

    override suspend fun generate(input: MarkdownFormattingInput): MarkdownFormattingOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId

        logger.debug("Formatting markdown for URL: {}, raw text length: {} chars", input.url, input.rawText.length)

        // For small inputs, process directly without chunking
        if (input.rawText.length < CHUNKING_THRESHOLD) {
            logger.debug("Input below chunking threshold, processing directly")
            val (markdown, tokenUsage) = processChunk(buildUserPrompt(input.rawText, input.popupText))
            val finalMarkdown = buildFinalMarkdown(input, markdown)
            return MarkdownFormattingOutput(markdown = finalMarkdown, tokenUsage = tokenUsage)
        }

        // Chunk the raw text and process in parallel
        val chunks = chunkText(input.rawText)
        logger.debug("Split input into {} chunks for parallel processing", chunks.size)

        return if (chunks.size <= 1) {
            // Single chunk - process directly
            val (markdown, tokenUsage) = processChunk(buildUserPrompt(input.rawText, input.popupText))
            val finalMarkdown = buildFinalMarkdown(input, markdown)
            MarkdownFormattingOutput(markdown = finalMarkdown, tokenUsage = tokenUsage)
        } else {
            // Multiple chunks - process in parallel
            processChunksInParallel(chunks, input)
        }
    }

    /**
     * Process multiple chunks in parallel.
     */
    private suspend fun processChunksInParallel(
        chunks: List<String>,
        input: MarkdownFormattingInput
    ): MarkdownFormattingOutput = coroutineScope {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        
        logger.debug("Processing {} chunks in parallel", chunks.size)

        // Process all chunks in parallel
        val results = chunks.mapIndexed { index, chunk ->
            async {
                logger.debug("Processing chunk {} of {} ({} chars)", index + 1, chunks.size, chunk.length)
                processChunk(buildUserPrompt(chunk, if (index == 0) input.popupText else null))
            }
        }.awaitAll()

        // Combine results in order
        val combinedMarkdown = results.joinToString("\n\n") { it.first.trim() }
        val aggregatedTokenUsage = results.fold(TokenUsageMetrics.empty(modelId)) { acc, (_, tokenUsage) ->
            TokenUsageMetrics(
                modelName = acc.modelName,
                promptTokens = acc.promptTokens + tokenUsage.promptTokens,
                outputTokens = acc.outputTokens + tokenUsage.outputTokens,
                totalTokens = acc.totalTokens + tokenUsage.totalTokens
            )
        }

        val finalMarkdown = buildFinalMarkdown(input, combinedMarkdown)

        logger.debug("Parallel processing complete, combined output: {} chars", finalMarkdown.length)

        MarkdownFormattingOutput(
            markdown = finalMarkdown,
            tokenUsage = aggregatedTokenUsage
        )
    }

    /**
     * Process a single chunk of text through the LLM.
     * Returns a Pair of (formatted markdown, token usage).
     */
    private suspend fun processChunk(userPrompt: String): Pair<String, TokenUsageMetrics> {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<MarkdownFormattingResponse>(this@MarkdownFormattingAgentGenAiImpl::class.simpleName!!) {
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

        return response.markdown.trim() to tokenUsage
    }

    // ========== Text Chunking Logic ==========

    /**
     * Chunks text into segments that can be processed in parallel.
     * Uses heuristic-based breakpoint detection to avoid splitting:
     * - Images (markdown image syntax)
     * - Tables (consecutive lines starting with |)
     * - Table context (blockquotes after tables)
     * - Mid-sentence
     */
    private fun chunkText(rawText: String): List<String> {
        val safeBreakpoints = findSafeBreakpoints(rawText)
        
        if (safeBreakpoints.isEmpty()) {
            return listOf(rawText)
        }

        val chunks = mutableListOf<String>()
        var currentStart = 0
        var currentSize = 0
        var lastBreakpoint = 0

        for (breakpoint in safeBreakpoints) {
            val segmentSize = breakpoint - lastBreakpoint

            if (currentSize + segmentSize > TARGET_CHUNK_SIZE && currentSize >= MIN_CHUNK_SIZE) {
                // Current chunk is big enough, start a new one
                val chunkContent = rawText.substring(currentStart, lastBreakpoint).trim()
                if (chunkContent.isNotEmpty()) {
                    chunks.add(chunkContent)
                }
                currentStart = lastBreakpoint
                currentSize = segmentSize
            } else {
                currentSize += segmentSize
            }
            lastBreakpoint = breakpoint
        }

        // Add remaining content
        val remaining = rawText.substring(currentStart).trim()
        if (remaining.isNotEmpty()) {
            chunks.add(remaining)
        }

        return chunks
    }

    /**
     * Finds safe breakpoints in the text where chunking can occur.
     * Safe breakpoints are positions where we can split without breaking:
     * - Tables (including their blockquote context)
     * - Images
     * - Sentences
     */
    private fun findSafeBreakpoints(rawText: String): List<Int> {
        val breakpoints = mutableSetOf<Int>()
        val lines = rawText.lines()
        var charIndex = 0
        var inTableBlock = false

        for (line in lines) {
            val lineEnd = charIndex + line.length + 1 // +1 for newline
            val trimmed = line.trim()

            when {
                // Image line - breakpoint BEFORE the image
                isImageLine(trimmed) -> {
                    breakpoints.add(charIndex)
                    breakpoints.add(lineEnd)
                    inTableBlock = false
                }
                // Table line
                isTableLine(trimmed) -> {
                    if (!inTableBlock) {
                        breakpoints.add(charIndex) // Break before table starts
                        inTableBlock = true
                    }
                }
                // Blockquote (table context) - keep with table
                inTableBlock && trimmed.startsWith(">") -> {
                    // Continue table block, don't add breakpoint
                }
                // End of table block
                inTableBlock -> {
                    if (trimmed.isNotEmpty()) {
                        breakpoints.add(charIndex) // Break after table ends
                        inTableBlock = false
                    }
                }
                // Blank line - natural paragraph break
                trimmed.isEmpty() -> {
                    breakpoints.add(lineEnd)
                }
            }

            charIndex = lineEnd
        }

        // Add sentence breaks for non-table/non-image areas
        val sentenceBreaks = findSentenceBreaks(rawText)
        breakpoints.addAll(sentenceBreaks)

        return breakpoints.filter { it in 1 until rawText.length }.sorted().distinct()
    }

    /**
     * Detects if a line is a markdown image reference.
     */
    private fun isImageLine(trimmedLine: String): Boolean {
        return trimmedLine.startsWith("![")
    }

    /**
     * Detects if a line is part of a markdown table.
     */
    private fun isTableLine(trimmedLine: String): Boolean {
        return trimmedLine.startsWith("|")
    }

    /**
     * Finds sentence boundaries using ICU4J BreakIterator.
     * Works across multiple languages including CJK.
     */
    private fun findSentenceBreaks(text: String): List<Int> {
        val breakIterator = BreakIterator.getSentenceInstance(Locale.ROOT)
        breakIterator.setText(text)

        val breaks = mutableListOf<Int>()
        var boundary = breakIterator.next()
        while (boundary != BreakIterator.DONE) {
            breaks.add(boundary)
            boundary = breakIterator.next()
        }
        return breaks
    }

    // ========== Prompt Building ==========

    /**
     * Builds the user prompt containing just the raw text for LLM formatting.
     */
    private fun buildUserPrompt(rawText: String, popupText: String?): String = buildString {
        if (!popupText.isNullOrBlank()) {
            appendLine("=== POPUP CONTENT ===")
            appendLine(popupText)
            appendLine()
        }

        appendLine("=== RAW EXTRACTED CONTENT ===")
        appendLine(rawText)
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
