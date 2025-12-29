package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ClassifiedSource
import io.deepsearch.domain.agents.IPreviewAnswerSynthesisAgent
import io.deepsearch.domain.agents.PreviewAnswerStreamItem
import io.deepsearch.domain.agents.PreviewAnswerSynthesisInput
import io.deepsearch.domain.agents.PreviewAnswerSynthesisOutput
import io.deepsearch.domain.agents.RelevantFact
import io.deepsearch.domain.agents.SourceClassification
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.flowWithRateLimitRetry
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Preview Answer Synthesis agent that generates answers from classified preview sources.
 * 
 * This agent performs internal filtering:
 * - Only uses facts where isInTable=false AND classification=OFFICIAL_LIVING_DOC
 * - Returns answerFound=false if no valid facts remain after filtering
 * 
 * Supports streaming answer generation.
 */
class PreviewAnswerSynthesisAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IPreviewAnswerSynthesisAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Answer generated from preview sources with confidence metrics")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("The answer to the query based on the provided facts. Must be directly supported by the facts.")
                    .build(),
                "answerFound" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether a complete, confident answer was found. True only if the facts fully answer the query.")
                    .build(),
                "reasoning" to Schema.builder()
                    .type("STRING")
                    .description("Brief explanation of how the answer was derived from the facts, or why the answer could not be found.")
                    .build()
            )
        )
        .required(listOf("answer", "answerFound", "reasoning"))
        .build()

    private val systemInstruction = """
        You are an answer synthesis agent. You generate answers based on pre-extracted facts from a website.

        Instructions:
        - ONLY use the facts provided in the input
        - Set answerFound=true ONLY if the facts completely answer the query
        - Partial answers should have answerFound=false

        - ANSWER FORMAT
           - Keep the answer concise and direct
           - Use markdown formatting where appropriate
           - Cite specific facts when possible
           - The answer should be in the same language as the query
           - If answerFound=false, still provide whatever partial information is available

        - REASONING
           - Briefly explain how you derived the answer from the facts
           - If answerFound=false, explain what's missing or uncertain

        Output Format:
        {
            "answer": "Your answer text",
            "answerFound": true/false,
            "reasoning": "Brief explanation"
        }
    """.trimIndent()

    @Serializable
    private data class SynthesisResponse(
        val answer: String,
        val answerFound: Boolean,
        val reasoning: String
    )

    /**
     * Filters facts to only include those from OFFICIAL_LIVING_DOC sources that are not in tables.
     */
    private fun filterValidFacts(sources: List<ClassifiedSource>): List<Pair<String, RelevantFact>> {
        return sources.flatMap { source ->
            source.relevantFacts
                .filter { fact ->
                    !fact.isInTable && fact.classification == SourceClassification.OFFICIAL_LIVING_DOC
                }
                .map { fact -> source.url to fact }
        }
    }

    override suspend fun generate(input: PreviewAnswerSynthesisInput): PreviewAnswerSynthesisOutput {
        logger.debug(
            "Generating preview answer for query: '{}', sources: {}",
            input.query,
            input.sourceClassifications.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        // Filter to valid facts only
        val validFacts = filterValidFacts(input.sourceClassifications)

        if (validFacts.isEmpty()) {
            logger.debug("No valid facts after filtering, returning answerFound=false")
            return PreviewAnswerSynthesisOutput(
                answer = "",
                answerFound = false,
                reasoning = "No valid facts found from official living documents (non-table content).",
                tokenUsage = tokenUsage
            )
        }

        val userPrompt = buildUserPrompt(input.query, validFacts)

        val response = retryLlmCall<SynthesisResponse>(this::class.simpleName!!) {
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
            "Preview answer complete: {} chars, answerFound: {}",
            response.answer.length,
            response.answerFound
        )

        return PreviewAnswerSynthesisOutput(
            answer = response.answer,
            answerFound = response.answerFound,
            reasoning = response.reasoning,
            tokenUsage = tokenUsage
        )
    }

    override fun generateStream(input: PreviewAnswerSynthesisInput): Flow<PreviewAnswerStreamItem> = flow {
        logger.debug(
            "Streaming preview answer for query: '{}', sources: {}",
            input.query,
            input.sourceClassifications.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId

        // Filter to valid facts only
        val validFacts = filterValidFacts(input.sourceClassifications)

        if (validFacts.isEmpty()) {
            logger.debug("No valid facts after filtering, emitting answerFound=false")
            emit(
                PreviewAnswerStreamItem.Complete(
                    tokenUsage = TokenUsageMetrics.empty(modelId),
                    answerFound = false,
                    reasoning = "No valid facts found from official living documents (non-table content)."
                )
            )
            return@flow
        }

        val userPrompt = buildUserPrompt(input.query, validFacts)

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
        flowWithRateLimitRetry(this@PreviewAnswerSynthesisAgentGenAiImpl::class.simpleName!!) {
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
                emit(PreviewAnswerStreamItem.Chunk(answerDelta))
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
            PreviewAnswerSynthesisAgentGenAiImpl::class.simpleName,
            accumulatedJson
        )

        // Parse the complete JSON to extract answerFound and reasoning
        val parsedResult = parseCompleteResponse(accumulatedJson)

        logger.debug(
            "Streaming preview answer complete: {} chars, answerFound: {}",
            lastAnswerLength,
            parsedResult.answerFound
        )

        emit(
            PreviewAnswerStreamItem.Complete(
                tokenUsage = tokenUsage,
                answerFound = parsedResult.answerFound,
                reasoning = parsedResult.reasoning
            )
        )
    }

    private fun parseCompleteResponse(jsonString: String): ParsedSynthesisResult {
        return try {
            val response = json.decodeFromString<SynthesisResponse>(jsonString)
            ParsedSynthesisResult(
                answerFound = response.answerFound,
                reasoning = response.reasoning
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse complete response: {}", e.message)
            ParsedSynthesisResult(
                answerFound = false,
                reasoning = "Failed to parse response"
            )
        }
    }

    private data class ParsedSynthesisResult(
        val answerFound: Boolean,
        val reasoning: String
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

    private fun buildUserPrompt(query: String, validFacts: List<Pair<String, RelevantFact>>): String {
        return buildString {
            appendLine("# Current Date")
            appendLine(java.time.LocalDate.now().toString())
            appendLine()
            appendLine("# Query")
            appendLine(query)
            appendLine()

            appendLine("# Extracted Facts from Official Living Documents")
            appendLine()

            // Group facts by URL for readability
            val factsByUrl = validFacts.groupBy({ it.first }, { it.second })
            
            factsByUrl.entries.forEachIndexed { index, (url, facts) ->
                appendLine("## Source ${index + 1}")
                appendLine("URL: $url")
                appendLine()
                appendLine("### Facts")
                facts.forEach { fact ->
                    appendLine("- ${fact.fact}")
                    appendLine("- ${fact.fact}")
                }
                appendLine()
                appendLine("---")
                appendLine()
            }

            appendLine()
            appendLine("# Instructions")
            appendLine("Generate an answer using ONLY the facts provided above.")
            appendLine("If the facts do not completely answer the query, set answerFound=false.")
            appendLine("Do not add any information not explicitly stated in the facts.")
        }
    }
}

