package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.AnswerStreamItem
import io.deepsearch.domain.agents.IPreviewAnswerSynthesisAgent
import io.deepsearch.domain.agents.PreviewAnswerSynthesisInput
import io.deepsearch.domain.agents.PreviewAnswerSynthesisOutput
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
 * Preview Answer Synthesis agent that generates answers from preview shortlisted sources.
 * 
 * This agent is CONSERVATIVE - it:
 * - Only cites absolute facts from shortlisted sources
 * - Prefers "I cannot confidently answer" over guessing
 * - Does not speculate or infer from ambiguous data
 * 
 * The preview answer is meant as an early exit for simple static content.
 */
class PreviewAnswerSynthesisAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IPreviewAnswerSynthesisAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Answer generated from preview sources with confidence metrics")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("The answer to the query based on extracted facts. Must be directly supported by the facts provided.")
                    .build(),
                "answerFound" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether a complete, confident answer was found. True only if the facts fully answer the query.")
                    .build(),
                "confidence" to Schema.builder()
                    .type("NUMBER")
                    .description("Confidence in the answer (0.0-1.0). Must be >= 0.9 for the answer to be used.")
                    .build()
            )
        )
        .required(listOf("answer", "answerFound", "confidence"))
        .build()

    private val systemInstruction = """
        You are an answer synthesis agent. You generate answers in response to a user query 
        based on the given pre-extracted facts from a website.

        Instructions:
        - ONLY use the extracted facts provided
        - Set answerFound=true ONLY if the facts completely answer the query
        - If the query asks for multiple items and you only have some, set answerFound=false
        - Partial answers should have confidence < 0.9

        - ANSWER FORMAT
           - Keep the answer concise and direct
           - Use markdown formatting where appropriate
           - Cite specific facts when possible
           - The answer should be in the same language as the query

        Output Format:
        {
            "answer": "Your answer text",
            "answerFound": true/false,
            "confidence": 0.95
        }
    """.trimIndent()

    @Serializable
    private data class SynthesisResponse(
        val answer: String,
        val answerFound: Boolean,
        val confidence: Float
    )

    override suspend fun generate(input: PreviewAnswerSynthesisInput): PreviewAnswerSynthesisOutput {
        logger.debug(
            "Generating preview answer for query: '{}', shortlist size: {}",
            input.query,
            input.shortlistedSources.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.shortlistedSources.isEmpty()) {
            logger.warn("No shortlisted sources provided, returning default message")
            return PreviewAnswerSynthesisOutput(
                answer = "No confident preview information available.",
                answerFound = false,
                confidence = 0f,
                tokenUsage = tokenUsage
            )
        }

        val userPrompt = buildUserPrompt(input)

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
            "Preview answer complete: {} chars, answerFound: {}, confidence: {}",
            response.answer.length,
            response.answerFound,
            response.confidence
        )

        return PreviewAnswerSynthesisOutput(
            answer = response.answer,
            answerFound = response.answerFound,
            confidence = response.confidence,
            tokenUsage = tokenUsage
        )
    }

    override fun generateStream(input: PreviewAnswerSynthesisInput): Flow<AnswerStreamItem> = flow {
        logger.debug(
            "Streaming preview answer for query: '{}', shortlist size: {}",
            input.query,
            input.shortlistedSources.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId

        if (input.shortlistedSources.isEmpty()) {
            logger.warn("No shortlisted sources provided, emitting default message")
            emit(AnswerStreamItem.Chunk("No confident preview information available."))
            emit(AnswerStreamItem.Complete(TokenUsageMetrics.empty(modelId), answerFound = false))
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
                emit(AnswerStreamItem.Chunk(answerDelta))
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

        // Extract answerFound from the complete JSON
        val answerFound = extractAnswerFound(accumulatedJson)

        logger.debug("Streaming preview answer complete: {} chars, answerFound: {}", lastAnswerLength, answerFound)
        emit(AnswerStreamItem.Complete(tokenUsage, answerFound))
    }

    private fun extractAnswerFound(json: String): Boolean {
        val regex = """"answerFound"\s*:\s*(true|false)""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(json) ?: return false
        return match.groupValues[1].equals("true", ignoreCase = true)
    }

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

        if (closingIdx > 0) {
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

    private fun buildUserPrompt(input: PreviewAnswerSynthesisInput): String {
        return buildString {
            appendLine("# Query")
            appendLine(input.query)
            appendLine()

            appendLine("# Extracted Facts from Preview Sources")
            appendLine()

            input.shortlistedSources.forEachIndexed { index, source ->
                appendLine("## Source ${index + 1}")
                appendLine("URL: ${source.url}")
                if (source.title != null) {
                    appendLine("Title: ${source.title}")
                }
                appendLine("Confidence: ${source.confidence}")
                appendLine("Justification: ${source.relevanceJustification}")
                appendLine()
                appendLine("### Facts")
                source.extractedFacts.forEach { fact ->
                    appendLine("- $fact")
                }
                appendLine()
                appendLine("---")
                appendLine()
            }

            appendLine()
            appendLine("# Instructions")
            appendLine("Generate an answer using ONLY the facts provided above.")
            appendLine("If the facts do not completely answer the query, set answerFound=false and confidence < 0.9.")
            appendLine("Do not add any information not explicitly stated in the facts.")
        }
    }
}
