package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IPreviewQuickAnswerAgent
import io.deepsearch.domain.agents.PreviewQuickAnswerInput
import io.deepsearch.domain.agents.PreviewQuickAnswerOutput
import io.deepsearch.domain.agents.PreviewQuickAnswerStreamItem
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.flowWithRateLimitRetry
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.models.valueobjects.PreviewShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.UrlContentResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Preview Quick Answer agent that curates high-quality sources from HTML previews
 * and generates an answer in a single LLM call.
 * 
 * This agent is VERY RESTRICTIVE - it only shortlists sources that:
 * - Contain unambiguous prose content
 * - Do NOT have tables, grids, images, or icons that affect the answer
 * - Have high confidence that the information directly answers the query
 * 
 * When confident, the agent also generates an answer using the extracted facts.
 * If uncertain, the agent does NOT shortlist - the main path will handle it.
 */
class PreviewQuickAnswerAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IPreviewQuickAnswerAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

    private val shortlistedSourceSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A shortlisted source with extracted facts and confidence")
        .properties(
            mapOf(
                "url" to Schema.builder().type("STRING")
                    .description("The URL of the source")
                    .build(),
                "extractedFacts" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("List of absolute facts extracted from clear prose content. Each fact should be a complete, standalone statement.")
                    .build(),
                "confidence" to Schema.builder().type("NUMBER")
                    .description("Confidence score (0.0-1.0) in the extracted facts. Must be >= 0.9 to be useful.")
                    .build(),
                "relevanceJustification" to Schema.builder().type("STRING")
                    .description("Brief reason for inclusion in shortlist")
                    .build()
            )
        )
        .required(listOf("url", "extractedFacts", "confidence", "relevanceJustification"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Updated shortlist of sources, confidence decision, and answer when confident")
        .properties(
            mapOf(
                "shortlist" to Schema.builder()
                    .type("ARRAY")
                    .items(shortlistedSourceSchema)
                    .description("List of shortlisted sources with extracted facts. Only include sources with high confidence.")
                    .build(),
                "isConfidentForAnswer" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether the shortlist contains sufficient high-confidence facts to answer the query. Only true if facts are unambiguous and complete.")
                    .build(),
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("The answer to the query based on extracted facts. Required when isConfidentForAnswer is true. Must be directly supported by the facts.")
                    .build(),
                "answerFound" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether a complete, confident answer was found. True only if the facts fully answer the query.")
                    .build()
            )
        )
        .required(listOf("shortlist", "isConfidentForAnswer", "answer", "answerFound"))
        .build()

    private val systemInstruction = $$"""
        Given a stream of HTMLs, shortlist relevant sources, extract facts, and generate an answer.

        Instructions:
        - The user will give you a continuous stream of HTMLs of a website
        - Shortlist relevant pages and extract facts from them
        - Only extract pages that are official pages. Pages like blog posts and publications can be outdated so should not be shortlisted
        - The URL of the source can provide valuable hints to whether the page is an official living document or stale

        - NEVER extract facts from tables, grids, or tabular data structures
           - <table>, <tr>, <td>, <th> elements → SKIP the source entirely
           - Repeated <div> patterns that look like rows/columns → SKIP
           - Pricing grids, comparison matrices, spec lists → SKIP

        - NEVER extract facts that depend on images, icons, or visual elements
           - <img> tags present and relevant to answer → SKIP the source
           - Icon classes (fa-, bi-, material-icons, etc.) → SKIP if relevant to answer
           - Charts, diagrams, screenshots → SKIP the source

        - ONLY extract facts from clear prose paragraphs
           - Well-formed sentences in <p>, <article>, <section>, or plain text
           - The meaning must be completely unambiguous from text alone
           - Examples: "Company X was founded in 2010", "The product costs $99/month", "Feature Y is available"

        - HIGH CONFIDENCE THRESHOLD
           - Set confidence >= 0.9 ONLY if you are absolutely certain
           - If there's ANY ambiguity, set confidence < 0.9 (which means we don't use it)
           - When in doubt, DO NOT shortlist the source

        - ANSWER GENERATION
           - When isConfidentForAnswer is true, generate a complete answer using ONLY the extracted facts
           - Keep the answer concise and direct
           - Use markdown formatting where appropriate
           - The answer should be in the same language as the query
           - Set answerFound=true ONLY if the facts completely answer the query
           - If the query asks for multiple items and you only have some, set answerFound=false

        - ANSWER SUFFICIENCY (isConfidentForAnswer)
           - Set true ONLY if you have extracted facts that DIRECTLY and COMPLETELY answer the query
           - Set false if facts are partial, uncertain, or incomplete
           - Set false if the query asks about something that typically requires tables/images (pricing, specs, comparisons)
           - When in doubt, set false - the main extraction pipeline will handle it

        Your goal is ACCURACY over COVERAGE. It is better to say "I cannot confidently extract" 
        than to extract an incorrect or ambiguous fact. The main multimodal extraction pipeline 
        will process everything properly - this is just an early exit optimization for simple content.

        Output Format:
        {
            "shortlist": [
                {
                    "url": "https://example.com/page",
                    "extractedFacts": ["Fact 1", "Fact 2"],
                    "confidence": 0.95,
                    "relevanceJustification": "Why this source is included"
                }
            ],
            "isConfidentForAnswer": true/false,
            "answer": "Your answer text based on extracted facts",
            "answerFound": true/false
        }
    """.trimIndent()

    @Serializable
    private data class LlmShortlistedSource(
        val url: String,
        val extractedFacts: List<String>,
        val confidence: Float,
        val relevanceJustification: String
    )

    @Serializable
    private data class QuickAnswerResponse(
        val shortlist: List<LlmShortlistedSource>,
        val isConfidentForAnswer: Boolean,
        val answer: String,
        val answerFound: Boolean
    )

    override suspend fun generate(input: PreviewQuickAnswerInput): PreviewQuickAnswerOutput {
        logger.debug(
            "Generating preview quick answer for query: '{}', current shortlist size: {}, new batch size: {}",
            input.query,
            input.currentShortlist.size,
            input.newHtmlBatch.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.newHtmlBatch.isEmpty()) {
            logger.debug("Empty HTML batch, returning current shortlist")
            return PreviewQuickAnswerOutput(
                updatedShortlist = input.currentShortlist,
                isConfidentForAnswer = false,
                answer = null,
                answerFound = false,
                tokenUsage = tokenUsage
            )
        }

        val userPrompt = buildUserPrompt(input)

        val response = retryLlmCall<QuickAnswerResponse>(this::class.simpleName!!) {
            val result = client.models.generateContent(
                modelId,
                userPrompt,
                GenerateContentConfig.builder()
                    .temperature(0F)
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

        logger.debug(
            "[{}] response: {}",
            PreviewQuickAnswerAgentGenAiImpl::class.simpleName,
            response
        )

        // Convert LLM response to domain model, filtering low confidence sources
        val updatedShortlist = response.shortlist
            .filter { it.confidence >= 0.9f && it.extractedFacts.isNotEmpty() }
            .map { llmSource ->
                val title = input.newHtmlBatch.find { it.url == llmSource.url }?.title
                    ?: input.currentShortlist.find { it.url == llmSource.url }?.title

                PreviewShortlistedSource(
                    url = llmSource.url,
                    title = title,
                    extractedFacts = llmSource.extractedFacts,
                    confidence = llmSource.confidence,
                    relevanceJustification = llmSource.relevanceJustification
                )
            }

        val isConfident = response.isConfidentForAnswer && updatedShortlist.isNotEmpty()

        logger.debug(
            "Preview quick answer complete: {} sources, isConfidentForAnswer: {}, answerFound: {}, answer length: {}",
            updatedShortlist.size,
            isConfident,
            response.answerFound,
            response.answer.length
        )

        return PreviewQuickAnswerOutput(
            updatedShortlist = updatedShortlist,
            isConfidentForAnswer = isConfident,
            answer = if (isConfident) response.answer else null,
            answerFound = response.answerFound && isConfident,
            tokenUsage = tokenUsage
        )
    }

    override fun generateStream(input: PreviewQuickAnswerInput): Flow<PreviewQuickAnswerStreamItem> = flow {
        logger.debug(
            "Streaming preview quick answer for query: '{}', current shortlist size: {}, new batch size: {}",
            input.query,
            input.currentShortlist.size,
            input.newHtmlBatch.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId

        if (input.newHtmlBatch.isEmpty()) {
            logger.debug("Empty HTML batch, returning current shortlist without streaming")
            emit(
                PreviewQuickAnswerStreamItem.Complete(
                    updatedShortlist = input.currentShortlist,
                    isConfidentForAnswer = false,
                    answerFound = false,
                    tokenUsage = TokenUsageMetrics.empty(modelId)
                )
            )
            return@flow
        }

        val userPrompt = buildUserPrompt(input)

        val config = GenerateContentConfig.builder()
            .temperature(0F)
            .responseSchema(outputSchema)
            .responseMimeType("application/json")
            .thinkingConfig(
                ThinkingConfig.builder()
                    .thinkingBudget(0)
                    .build()
            )
            .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
            .build()

        var accumulatedJson = ""
        var lastAnswerLength = 0
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        // Use flowWithRateLimitRetry to handle 429 errors by restarting the stream
        flowWithRateLimitRetry(this@PreviewQuickAnswerAgentGenAiImpl::class.simpleName!!) {
            // Reset state on each retry attempt
            accumulatedJson = ""
            lastAnswerLength = 0
            tokenUsage = TokenUsageMetrics.empty(modelId)
            client.models.generateContentStream(modelId, userPrompt, config)
        }.collect { response ->
            val chunkText = response.text() ?: return@collect
            accumulatedJson += chunkText

            // Extract answer delta from accumulated JSON
            val answerDelta = extractAnswerDelta(accumulatedJson, lastAnswerLength)
            if (answerDelta.isNotEmpty()) {
                lastAnswerLength += answerDelta.length
                emit(PreviewQuickAnswerStreamItem.AnswerChunk(answerDelta))
            }

            // Token usage is available in the last chunk
            response.usageMetadata().ifPresent { metadata ->
                val totalTokens = metadata.totalTokenCount().orElse(0)
                if (totalTokens > 0) {
                    tokenUsage = TokenUsageMetrics(
                        modelName = modelId,
                        promptTokens = metadata.promptTokenCount().orElse(0),
                        outputTokens = metadata.candidatesTokenCount().orElse(0),
                        totalTokens = totalTokens
                    )
                }
            }
        }

        logger.debug(
            "[{}] response: {}",
            PreviewQuickAnswerAgentGenAiImpl::class.simpleName,
            accumulatedJson
        )

        // Parse the complete JSON to extract shortlist and metadata
        val parsedResult = parseCompleteResponse(accumulatedJson, input)

        logger.debug(
            "Streaming preview quick answer complete: {} sources, isConfident: {}, answerFound: {}",
            parsedResult.updatedShortlist.size,
            parsedResult.isConfidentForAnswer,
            parsedResult.answerFound
        )

        emit(
            PreviewQuickAnswerStreamItem.Complete(
                updatedShortlist = parsedResult.updatedShortlist,
                isConfidentForAnswer = parsedResult.isConfidentForAnswer,
                answerFound = parsedResult.answerFound,
                tokenUsage = tokenUsage
            )
        )
    }

    private fun parseCompleteResponse(
        jsonString: String,
        input: PreviewQuickAnswerInput
    ): ParsedQuickAnswerResult {
        return try {
            val response = json.decodeFromString<QuickAnswerResponse>(jsonString)

            val updatedShortlist = response.shortlist
                .filter { it.confidence >= 0.9f && it.extractedFacts.isNotEmpty() }
                .map { llmSource ->
                    val title = input.newHtmlBatch.find { it.url == llmSource.url }?.title
                        ?: input.currentShortlist.find { it.url == llmSource.url }?.title

                    PreviewShortlistedSource(
                        url = llmSource.url,
                        title = title,
                        extractedFacts = llmSource.extractedFacts,
                        confidence = llmSource.confidence,
                        relevanceJustification = llmSource.relevanceJustification
                    )
                }

            val isConfident = response.isConfidentForAnswer && updatedShortlist.isNotEmpty()

            ParsedQuickAnswerResult(
                updatedShortlist = updatedShortlist,
                isConfidentForAnswer = isConfident,
                answerFound = response.answerFound && isConfident
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse complete response, falling back to current shortlist: {}", e.message)
            ParsedQuickAnswerResult(
                updatedShortlist = input.currentShortlist,
                isConfidentForAnswer = false,
                answerFound = false
            )
        }
    }

    private data class ParsedQuickAnswerResult(
        val updatedShortlist: List<PreviewShortlistedSource>,
        val isConfidentForAnswer: Boolean,
        val answerFound: Boolean
    )

    private fun extractAnswerDelta(accumulatedJson: String, previousLength: Int): String {
        val prefixNoSpace = "\"answer\":\""
        val prefixWithSpace = "\"answer\": \""

        var startIdx = accumulatedJson.indexOf(prefixNoSpace)
        var prefixLength = prefixNoSpace.length

        if (startIdx == -1) {
            startIdx = accumulatedJson.indexOf(prefixWithSpace)
            prefixLength = prefixWithSpace.length
        }

        if (startIdx == -1) return ""

        val contentStart = startIdx + prefixLength
        if (contentStart >= accumulatedJson.length) return ""

        var content = accumulatedJson.substring(contentStart)

        // Find the closing quote
        var closingIdx = -1
        var i = 0
        while (i < content.length) {
            if (content[i] == '\\') {
                i += 2
                continue
            }
            if (content[i] == '"') {
                val remaining = content.substring(i + 1).trimStart()
                if (remaining.startsWith(",") || remaining.startsWith("}")) {
                    closingIdx = i
                    break
                }
            }
            i++
        }

        if (closingIdx >= 0) {
            content = content.substring(0, closingIdx)
        }

        val unescapedContent = unescapeJsonString(content)

        return if (unescapedContent.length > previousLength) {
            unescapedContent.substring(previousLength)
        } else {
            ""
        }
    }

    private fun unescapeJsonString(jsonString: String): String {
        return jsonString
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun buildUserPrompt(input: PreviewQuickAnswerInput): String {
        return buildString {
            // Include current date for temporal context
            appendLine("# Current Date")
            appendLine(java.time.LocalDate.now().toString())
            appendLine()

            appendLine("# Query")
            appendLine(input.query)
            appendLine()

            if (input.currentShortlist.isNotEmpty()) {
                appendLine("# Current Shortlist")
                input.currentShortlist.forEachIndexed { index, source ->
                    appendLine("## Source ${index + 1}")
                    appendLine("URL: ${source.url}")
                    appendLine("Confidence: ${source.confidence}")
                    appendLine("Extracted Facts:")
                    source.extractedFacts.forEach { fact ->
                        appendLine("  - $fact")
                    }
                    appendLine("Justification: ${source.relevanceJustification}")
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }

            appendLine("# New HTML Sources to Evaluate")
            input.newHtmlBatch.forEachIndexed { index, source ->
                appendLine("## New Source ${index + 1}")
                appendLine("URL: ${source.url}")
                if (source.title != null) {
                    appendLine("Title: ${source.title}")
                }
                if (source.description != null) {
                    appendLine("Description: ${source.description}")
                }
                appendLine()
                appendLine("### HTML Content")
                appendLine("```html")
                appendLine(source.cleanedHtml)
                appendLine("```")
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }
}

