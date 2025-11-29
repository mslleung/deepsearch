package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.AnswerStreamItem
import io.deepsearch.domain.agents.AnswerSynthesisInput
import io.deepsearch.domain.agents.AnswerSynthesisOutput
import io.deepsearch.domain.agents.IAnswerSynthesisAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Answer Synthesis agent that generates a comprehensive answer from shortlisted sources.
 * Focuses solely on building a high-quality answer from pre-curated sources.
 */
class AnswerSynthesisAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IAnswerSynthesisAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Comprehensive answer to the query")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Comprehensive answer to the search query based on shortlisted sources")
                    .build()
            )
        )
        .required(listOf("answer"))
        .build()

    private val systemInstruction = """
        You are an answer synthesis agent that generates comprehensive answers from curated sources.
        
        Answer Quality:
        - The answer should be as comprehensive as possible based on the provided sources
        - The answer should be standalone and serve as a direct answer to the user query
        - If the user query is a statement instead of a question, focus on supplying relevant information
        - Only include information that supports the answer in addressing the query
        - Do not invent information not present in the sources
        - Use markdown styling as applicable (headings, lists, bold, etc.)
        - The answer should be in the same language as the input query
        - The answer should be placed in a JSON structured output
        
        Source Prioritization:
        - All provided sources have been pre-curated for quality and relevance
        - If sources contain conflicting information, prioritize sources with:
          1. Higher temporal relevance (more stable/official content)
          2. Higher authority (official pages over blog posts)
          3. Higher content relevance (more directly answers the query)
        - No need to note discrepancies, just include the most credible information
        - Synthesize information across sources when they complement each other
        
        Handling Insufficient Information:
        - If the sources lack sufficient information to answer the query, state that clearly
        - Only provide what can be substantiated by the sources
        - Do not speculate or fill gaps with external knowledge
        
        Expected Output Shape:
        {
            "answer": "your comprehensive answer text"
        }
    """.trimIndent()

    @Serializable
    private data class SynthesisResponse(
        val answer: String
    )

    override suspend fun generate(input: AnswerSynthesisInput): AnswerSynthesisOutput {
        logger.debug(
            "Generating answer synthesis for query: '{}', shortlist size: {}",
            input.query,
            input.shortlistedSources.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.shortlistedSources.isEmpty()) {
            logger.warn("No shortlisted sources provided, returning default message")
            return AnswerSynthesisOutput(
                answer = "No information found to answer the query.",
                tokenUsage = tokenUsage
            )
        }

        val userPrompt = buildUserPrompt(input)

        val response = retryLlmCall<SynthesisResponse> {
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
            "Answer synthesis complete: {} chars",
            response.answer.length
        )

        return AnswerSynthesisOutput(
            answer = response.answer,
            tokenUsage = tokenUsage
        )
    }

    override fun generateStream(input: AnswerSynthesisInput): Flow<AnswerStreamItem> = flow {
        logger.debug(
            "Streaming answer synthesis for query: '{}', shortlist size: {}",
            input.query,
            input.shortlistedSources.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId

        if (input.shortlistedSources.isEmpty()) {
            logger.warn("No shortlisted sources provided, emitting default message")
            emit(AnswerStreamItem.Chunk("No information found to answer the query."))
            emit(AnswerStreamItem.Complete(TokenUsageMetrics.empty(modelId)))
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

        val responseStream = client.models.generateContentStream(modelId, userPrompt, config)

        var accumulatedJson = ""
        var lastAnswerLength = 0
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        for (response in responseStream) {
            val chunkText = response.text() ?: continue
            accumulatedJson += chunkText

            // Extract answer delta from accumulated JSON: {"answer": "..."}
            val answerDelta = extractAnswerDelta(accumulatedJson, lastAnswerLength)
            if (answerDelta.isNotEmpty()) {
                lastAnswerLength += answerDelta.length
                emit(AnswerStreamItem.Chunk(answerDelta))
            }

            // Token usage is available in the last chunk (per Gemini streaming docs)
            response.usageMetadata().ifPresent { metadata ->
                val promptTokens = metadata.promptTokenCount().orElse(0)
                val outputTokens = metadata.candidatesTokenCount().orElse(0)
                val totalTokens = metadata.totalTokenCount().orElse(0)
                // Only update if we got actual values (last chunk has non-zero values)
                if (totalTokens > 0) {
                    tokenUsage = TokenUsageMetrics(
                        modelName = modelId,
                        promptTokens = promptTokens,
                        outputTokens = outputTokens,
                        totalTokens = totalTokens
                    )
                }
            }
        }

        logger.debug("Streaming answer synthesis complete: {} chars total", lastAnswerLength)
        emit(AnswerStreamItem.Complete(tokenUsage))
    }

    /**
     * Extract the new portion of the answer from accumulated JSON.
     * The JSON format is: {"answer": "...text..."}
     */
    private fun extractAnswerDelta(accumulatedJson: String, previousLength: Int): String {
        // Find the start of the answer value
        val prefix = "\"answer\":\""
        val startIdx = accumulatedJson.indexOf(prefix)
        if (startIdx == -1) return ""

        val contentStart = startIdx + prefix.length
        if (contentStart >= accumulatedJson.length) return ""

        // Get the content after the prefix, handling potential incomplete JSON
        var content = accumulatedJson.substring(contentStart)

        // Remove trailing incomplete JSON (closing quotes/braces if present)
        if (content.endsWith("\"}")) {
            content = content.dropLast(2)
        } else if (content.endsWith("\"")) {
            content = content.dropLast(1)
        }

        // Unescape JSON string escape sequences for the new content
        val unescapedContent = unescapeJsonString(content)

        // Return only the new portion
        return if (unescapedContent.length > previousLength) {
            unescapedContent.substring(previousLength)
        } else {
            ""
        }
    }

    /**
     * Unescape common JSON string escape sequences.
     */
    private fun unescapeJsonString(jsonString: String): String {
        return jsonString
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun buildUserPrompt(input: AnswerSynthesisInput): String {
        return buildString {
            appendLine("# Query")
            appendLine(input.query)
            appendLine()
            appendLine("# Shortlisted Sources")
            appendLine()

            input.shortlistedSources.forEachIndexed { index, source ->
                appendLine("## Source ${index + 1}")
                appendLine("URL: ${source.url}")
                appendLine("Source Classification: ${source.sourceClassification}")
                appendLine("Content Date: ${source.contentDate ?: "Not found"}")
                appendLine("Answer Type: ${source.answerType}")
                appendLine("Relevance Justification: ${source.relevanceJustification}")
                appendLine()
                appendLine("### Content")
                appendLine(source.markdown)
                appendLine()
                appendLine("---")
                appendLine()
            }

            appendLine()
            appendLine("# Instructions")
            appendLine("Generate a comprehensive answer to the query using the shortlisted sources above.")
            appendLine("Prioritize sources with DIRECT_ANSWER type and OFFICIAL_LIVING_DOC classification when synthesizing information.")
        }
    }
}

