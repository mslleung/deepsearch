package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.agents.StreamingAnswerSynthesisOutput
import io.deepsearch.domain.agents.StreamingAnswerStreamItem
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.flowWithRateLimitRetry
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.AnswerType
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Streaming Answer Synthesis agent that generates comprehensive answers from extracted facts.
 * Receives facts (not full markdown content) from the shortlist agent and synthesizes them into an answer.
 */
class StreamingAnswerSynthesisAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IStreamingAnswerSynthesisAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Comprehensive answer to the query with answer type classification, reasoning, and referenced images")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Comprehensive answer to the search query based on the extracted facts")
                    .build(),
                "answerType" to Schema.builder()
                    .type("STRING")
                    .description("Classification of how well the answer addresses the query: DIRECT_ANSWER (facts explicitly and comprehensively answer the query), INFERRED_ANSWER (answer derived from combining/interpreting facts), or PARTIAL_MENTION (facts only tangentially relate to the query)")
                    .enum_(listOf("DIRECT_ANSWER", "INFERRED_ANSWER", "PARTIAL_MENTION"))
                    .build(),
                "reasoning" to Schema.builder()
                    .type("STRING")
                    .description("Brief explanation of how the answer was derived from the facts, or why the answer could not be found.")
                    .build(),
                "imageIds" to Schema.builder()
                    .type("ARRAY")
                    .description("List of image IDs from the sources that should be displayed with the answer.")
                    .items(Schema.builder().type("STRING").build())
                    .build()
            )
        )
        .required(listOf("answer", "answerType", "reasoning", "imageIds"))
        .build()

    private val systemInstruction = """
        You are an answer synthesis agent that generates comprehensive answers from pre-extracted facts.
        
        Answer Quality:
        - The answer should be as comprehensive as possible based on the provided facts
        - The answer should be standalone and serve as a straightforward and direct answer to the user query
        - Only include information that is present in the provided facts
        - Do not invent information not present in the facts
        - Use markdown styling as applicable (headings, lists, bold, etc.)
        - The answer should be in the same language as the input query
        - The answer should be placed in a JSON structured output
        
        Fact Prioritization:
        - All provided facts have been pre-extracted and curated for quality and relevance
        - If facts contain conflicting information, prioritize facts with:
          1. OFFICIAL_LIVING_DOC classification (most current and authoritative)
          2. OFFICIAL_SNAPSHOT classification (dated but official)
          3. OTHERS classification (external sources)
        - No need to note discrepancies, just include the most credible information
        - Synthesize information across facts when they complement each other
        
        Handling Insufficient Information:
        - If the facts lack sufficient information to answer the query, state that clearly
        - Only provide what can be substantiated by the facts
        - Do not speculate or fill gaps with external knowledge
        
        Answer Type Classification:
        Choose the answerType that best describes how the facts address the CORE INTENT of the query:
        
        - DIRECT_ANSWER: Use ONLY when the facts explicitly and comprehensively answer the PRIMARY question.
          Examples:
          - Query "What is the pricing?" → Facts contain main subscription plans/tiers ($99/mo Pro, $299/mo Premium, etc.)
          - Query "What are the features?" → Facts list the main product features with details
          - The facts directly state the PRIMARY information the user is asking for
          CRITICAL: Answering a narrow subset or tangential aspect does NOT qualify as DIRECT_ANSWER.
          For example, if asked "What is the pricing?" and facts only contain API usage fees, add-on costs,
          or third-party fees (not the main subscription plans), that is PARTIAL_MENTION, not DIRECT_ANSWER.
        
        - INFERRED_ANSWER: Use when the answer can be derived by combining or interpreting facts.
          Examples:
          - Query "Is it affordable?" → Facts show prices; you infer affordability
          - Query "Can it integrate with Shopify?" → Facts mention e-commerce integrations
          - The answer requires some interpretation but is well-supported by facts
        
        - PARTIAL_MENTION: Use when facts only tangentially relate to the query or answer only a SUBSET of what was asked. BE STRICT - this is the default when uncertain.
          Examples:
          - Query "What is the pricing?" → Facts only mention "start for free" without actual subscription tiers
          - Query "What is the pricing?" → Facts only contain API/usage fees or third-party costs without main subscription plans
          - Query "What are the features?" → Facts only mention the product name without listing features
          - The facts touch on the topic but don't actually answer the CORE of what was asked
          - You acknowledge "specific details are not available" or similar
        
        IMPORTANT: When in doubt, choose PARTIAL_MENTION. It's better to continue searching than to prematurely conclude with incomplete information. Answering a tangential aspect of a broad query (e.g., WhatsApp API fees when asked about overall product pricing) should be PARTIAL_MENTION.
      
        Reasoning:
        - Briefly explain how you derived the answer from the facts
        - If answerType is PARTIAL_MENTION, explain what specific information is missing
        
        Expected Output Shape:
        {
            "answer": "your comprehensive answer text",
            "answerType": "DIRECT_ANSWER" | "INFERRED_ANSWER" | "PARTIAL_MENTION",
            "reasoning": "brief explanation of how the answer was derived and why this answerType was chosen",
            "imageIds": ["img-xxx"]  // IDs of relevant images from sources, empty array if none
        }
    """.trimIndent()

    @Serializable
    private data class SynthesisResponse(
        val answer: String,
        val answerType: AnswerType,
        val reasoning: String,
        val imageIds: List<String> = emptyList()
    )

    override suspend fun generate(input: StreamingAnswerSynthesisInput): StreamingAnswerSynthesisOutput {
        logger.debug(
            "Generating answer synthesis for query: '{}' (expanded: '{}'), shortlist size: {}, total facts: {}",
            input.query,
            input.expandedQuery ?: "none",
            input.shortlistedSources.size,
            input.shortlistedSources.sumOf { it.relevantFacts.size }
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.shortlistedSources.isEmpty() || input.shortlistedSources.all { it.relevantFacts.isEmpty() }) {
            logger.warn("No facts provided, returning default message")
            return StreamingAnswerSynthesisOutput(
                answer = "No information found to answer the query.",
                answerType = AnswerType.PARTIAL_MENTION,
                reasoning = "No facts available from the provided sources.",
                tokenUsage = tokenUsage
            )
        }

        val userPrompt = buildUserPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<SynthesisResponse>(this@StreamingAnswerSynthesisAgentGenAiImpl::class.simpleName!!) {
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
        }

        logger.debug(
            "[{}] response: {}",
            StreamingAnswerSynthesisAgentGenAiImpl::class.simpleName,
            response
        )

        logger.debug(
            "Answer synthesis complete: {} chars, answerType: {}, {} images",
            response.answer.length,
            response.answerType,
            response.imageIds.size
        )

        return StreamingAnswerSynthesisOutput(
            answer = response.answer,
            answerType = response.answerType,
            reasoning = response.reasoning,
            imageIds = response.imageIds,
            tokenUsage = tokenUsage
        )
    }

    override fun generateStream(input: StreamingAnswerSynthesisInput): Flow<StreamingAnswerStreamItem> = flow {
        logger.debug(
            "Streaming answer synthesis for query: '{}' (expanded: '{}'), shortlist size: {}, total facts: {}",
            input.query,
            input.expandedQuery ?: "none",
            input.shortlistedSources.size,
            input.shortlistedSources.sumOf { it.relevantFacts.size }
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId

        if (input.shortlistedSources.isEmpty() || input.shortlistedSources.all { it.relevantFacts.isEmpty() }) {
            logger.warn("No facts provided, emitting default message")
            emit(StreamingAnswerStreamItem.Chunk("No information found to answer the query."))
            emit(StreamingAnswerStreamItem.Complete(
                tokenUsage = TokenUsageMetrics.empty(modelId),
                answerType = AnswerType.PARTIAL_MENTION,
                reasoning = "No facts available from the provided sources."
            ))
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
        // Run on IO dispatcher since Gemini SDK makes blocking HTTP calls
        flowWithRateLimitRetry(this@StreamingAnswerSynthesisAgentGenAiImpl::class.simpleName!!) {
            // Reset state on each retry attempt
            accumulatedJson = ""
            lastAnswerLength = 0
            tokenUsage = TokenUsageMetrics.empty(modelId)
            client.models.generateContentStream(modelId, userPrompt, config)
        }.flowOn(dispatcherProvider.io).collect { response ->
            val chunkText = response.text() ?: return@collect
            accumulatedJson += chunkText
            logger.trace("Accumulated JSON so far ({} chars): {}", accumulatedJson.length, 
                accumulatedJson.take(200).replace("\n", "\\n"))

            // Extract answer delta from accumulated JSON: {"answer": "..."}
            val answerDelta = extractAnswerDelta(accumulatedJson, lastAnswerLength)
            if (answerDelta.isNotEmpty()) {
                lastAnswerLength += answerDelta.length
                logger.trace("Emitting answer chunk ({} chars): {}", answerDelta.length, 
                    answerDelta.take(100).replace("\n", "\\n"))
                emit(StreamingAnswerStreamItem.Chunk(answerDelta))
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

        logger.debug(
            "[{}] response: {}",
            StreamingAnswerSynthesisAgentGenAiImpl::class.simpleName,
            accumulatedJson
        )

        // Extract answerType, reasoning, and imageIds from the complete JSON
        val answerType = extractAnswerType(accumulatedJson)
        val reasoning = extractReasoning(accumulatedJson)
        val imageIds = extractImageIds(accumulatedJson)
        
        logger.debug("Streaming answer synthesis complete: {} chars total, answerType: {}, {} images", lastAnswerLength, answerType, imageIds.size)
        emit(StreamingAnswerStreamItem.Complete(tokenUsage, answerType, reasoning, imageIds))
    }

    /**
     * Extract answerType enum from accumulated JSON.
     * The JSON format is: {"answer": "...", "answerType": "DIRECT_ANSWER", ...}
     * Defaults to PARTIAL_MENTION if not found or invalid.
     */
    private fun extractAnswerType(json: String): AnswerType {
        val regex = """"answerType"\s*:\s*"(DIRECT_ANSWER|INFERRED_ANSWER|PARTIAL_MENTION)"""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(json) ?: return AnswerType.PARTIAL_MENTION
        return try {
            AnswerType.valueOf(match.groupValues[1].uppercase())
        } catch (e: IllegalArgumentException) {
            AnswerType.PARTIAL_MENTION
        }
    }

    /**
     * Extract reasoning string from accumulated JSON.
     * The JSON format is: {"answer": "...", "reasoning": "..."}
     */
    private fun extractReasoning(json: String): String {
        val regex = """"reasoning"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        val match = regex.find(json) ?: return ""
        return unescapeJsonString(match.groupValues[1])
    }

    /**
     * Extract imageIds array from accumulated JSON.
     * The JSON format is: {"answer": "...", "imageIds": ["img-xxx"]}
     */
    private fun extractImageIds(json: String): List<String> {
        val regex = """"imageIds"\s*:\s*\[([^\]]*)\]""".toRegex()
        val match = regex.find(json) ?: return emptyList()
        
        val arrayContent = match.groupValues[1]
        if (arrayContent.isBlank()) return emptyList()
        
        // Extract individual string values from the array
        val stringRegex = """"([^"]+)"""".toRegex()
        return stringRegex.findAll(arrayContent)
            .map { it.groupValues[1] }
            .toList()
    }

    /**
     * Extract the new portion of the answer from accumulated JSON.
     * The JSON format is: {"answer": "...text...", "imageIds": [...]}
     */
    private fun extractAnswerDelta(accumulatedJson: String, previousLength: Int): String {
        // Find the start of the answer value - handle both with and without space after colon
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

        // Get the content after the prefix
        var content = accumulatedJson.substring(contentStart)

        // Find the closing quote of the answer field
        var closingIdx = -1
        var i = 0
        while (i < content.length) {
            if (content[i] == '\\') {
                // Skip escaped character
                i += 2
                continue
            }
            if (content[i] == '"') {
                // Found a potential closing quote, check what follows
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

    private fun buildUserPrompt(input: StreamingAnswerSynthesisInput): String {
        return buildString {
            appendLine("# Query")
            appendLine(input.effectiveQuery)
            appendLine()
            appendLine("# Extracted Facts from Shortlisted Sources")
            appendLine()

            input.shortlistedSources.forEachIndexed { index, source ->
                if (source.relevantFacts.isNotEmpty()) {
                    appendLine("## Source ${index + 1}")
                    appendLine("URL: ${source.url}")
                    appendLine("Source Classification: ${source.sourceClassification}")
                    appendLine("Content Date: ${source.contentDate ?: "Not found"}")
                    appendLine("Answer Type: ${source.answerType}")
                    appendLine("Relevance Justification: ${source.relevanceJustification}")
                    
                    if (source.relevantImageIds.isNotEmpty()) {
                        appendLine("Relevant Images: ${source.relevantImageIds.joinToString(", ")}")
                    }
                    
                    appendLine()
                    appendLine("### Facts")
                    source.relevantFacts.forEach { fact ->
                        appendLine("- [${fact.sourceClassification}] ${fact.fact}")
                    }
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }

            appendLine()
            appendLine("# Instructions")
            appendLine("Generate a comprehensive answer to the query using the extracted facts above.")
            appendLine("Prioritize facts from OFFICIAL_LIVING_DOC sources when synthesizing information.")
            appendLine("If relevant images are listed, include their IDs in the imageIds array.")
        }
    }
}

