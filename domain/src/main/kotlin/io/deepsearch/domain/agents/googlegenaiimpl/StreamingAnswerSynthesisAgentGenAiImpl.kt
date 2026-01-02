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
import io.deepsearch.domain.models.valueobjects.AnswerStatus
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
 * Receives facts (not full markdown content) from the source eval agents and synthesizes them into an answer.
 */
class StreamingAnswerSynthesisAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IStreamingAnswerSynthesisAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Comprehensive answer with status for feedback loop and optional follow-up queries")
        .properties(
            mapOf(
                "reasoning" to Schema.builder()
                    .type("STRING")
                    .description("Brief explanation of how the answer was derived from the facts, why this status was chosen, and what information is missing (if applicable).")
                    .build(),
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Comprehensive answer to the search query based on the extracted facts")
                    .build(),
                "citedSourceUrls" to Schema.builder()
                    .type("ARRAY")
                    .description("URLs of sources whose facts were actually used/cited in generating the answer. Only include sources that contributed to the answer content.")
                    .items(Schema.builder().type("STRING").build())
                    .build(),
                "status" to Schema.builder()
                    .type("STRING")
                    .description("COMPLETE if the answer fully addresses the query with sufficient authoritative sources. NEED_MORE_INFORMATION if the answer is partial or lacks authoritative sources and more searching is needed.")
                    .enum_(listOf("COMPLETE", "NEED_MORE_INFORMATION"))
                    .build(),
                "followUpQueries" to Schema.builder()
                    .type("ARRAY")
                    .description("Targeted search queries to find missing information. Required when status=NEED_MORE_INFORMATION. Each query should be specific and focused on a gap in the current answer. Do NOT include queries that have already been searched.")
                    .items(Schema.builder().type("STRING").build())
                    .build(),
                "imageIds" to Schema.builder()
                    .type("ARRAY")
                    .description("List of image IDs from the sources that should be displayed with the answer.")
                    .items(Schema.builder().type("STRING").build())
                    .build()
            )
        )
        .required(listOf("reasoning", "answer", "citedSourceUrls", "status", "followUpQueries", "imageIds"))
        .build()

    private val systemInstruction = $$"""
        You are an answer synthesis agent that generates comprehensive answers from pre-extracted facts.
        You are part of a feedback loop that continues searching until the answer is complete.
        
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
        - If the facts lack sufficient information to answer the query, provide the best answer possible with available facts
        - Only provide what can be substantiated by the facts
        - Do not speculate or fill gaps with external knowledge
        - Set status=NEED_MORE_INFORMATION and suggest targeted follow-up queries
        
        ## Status Decision (CRITICAL - drives the feedback loop):
        
        - **COMPLETE**: The answer fully addresses the query with sufficient authoritative sources.
          Use COMPLETE when:
          - The core question is answered comprehensively
          - Information comes from authoritative sources (preferably OFFICIAL_LIVING_DOC)
          - No critical gaps remain in the answer
          - Additional searching would not significantly improve the answer
        
        - **NEED_MORE_INFORMATION**: The answer is partial or lacks authoritative sources.
          Use NEED_MORE_INFORMATION when:
          - The answer only addresses a subset of what was asked
          - Information is from lower-quality sources when official sources likely exist
          - There are obvious gaps that targeted searches could fill
          - The query has multiple facets and not all are covered
          
          When NEED_MORE_INFORMATION, you MUST provide:
          - followUpQueries: 1-3 targeted search queries to find the missing info
          
        ## Follow-up Query Guidelines:
        - Make queries specific and focused on the gap
        - Do NOT suggest queries that have already been searched (check the "Previously Searched Queries" list)
        - Queries will be automatically prefixed with site: for the target domain
        - Examples:
          - "pricing plans subscription tiers"
          - "enterprise features comparison"
          - "API rate limits documentation"
      
        ## Reasoning (output FIRST before answer):
        - Briefly explain how you will derive the answer from the facts
        - Explain why you chose the status (COMPLETE vs NEED_MORE_INFORMATION)
        - If NEED_MORE_INFORMATION, explain what is missing
        
        ## Citation Source URLs:
        - List the URLs of sources whose facts you actually used in generating the answer
        - Only include sources that contributed information to the answer
        
        Expected Output Shape (fields in this exact order):
        {
            "reasoning": "brief explanation of how the answer was derived and why this status was chosen",
            "answer": "your comprehensive answer text",
            "citedSourceUrls": ["https://example.com/pricing", "https://example.com/features"],
            "status": "COMPLETE" | "NEED_MORE_INFORMATION",
            "followUpQueries": ["query1", "query2"],
            "imageIds": ["img-xxx"]
        }
    """.trimIndent()

    @Serializable
    private data class SynthesisResponse(
        val reasoning: String,
        val answer: String,
        val citedSourceUrls: List<String> = emptyList(),
        val status: AnswerStatus,
        val followUpQueries: List<String> = emptyList(),
        val imageIds: List<String> = emptyList()
    )

    override suspend fun generate(input: StreamingAnswerSynthesisInput): StreamingAnswerSynthesisOutput {
        logger.debug(
            "Generating answer synthesis for query: '{}', sources: {}, total facts: {}, previouslySearched: {}",
            input.query,
            input.evaluatedSources.size,
            input.evaluatedSources.sumOf { it.relevantFacts.size },
            input.previouslySearchedQueries.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.evaluatedSources.isEmpty() || input.evaluatedSources.all { it.relevantFacts.isEmpty() }) {
            logger.warn("No facts provided, returning default message")
            return StreamingAnswerSynthesisOutput(
                reasoning = "No facts available from the provided sources.",
                answer = "No information found to answer the query.",
                citedSourceUrls = emptyList(),
                status = AnswerStatus.NEED_MORE_INFORMATION,
                followUpQueries = listOf(input.query), // Retry the original query
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
            "Answer synthesis complete: {} chars, status: {}, {} images, citedSources: {}, followUpQueries: {}",
            response.answer.length,
            response.status,
            response.imageIds.size,
            response.citedSourceUrls.size,
            response.followUpQueries.size
        )

        return StreamingAnswerSynthesisOutput(
            reasoning = response.reasoning,
            answer = response.answer,
            citedSourceUrls = response.citedSourceUrls,
            status = response.status,
            followUpQueries = response.followUpQueries,
            imageIds = response.imageIds,
            tokenUsage = tokenUsage
        )
    }

    override fun generateStream(input: StreamingAnswerSynthesisInput): Flow<StreamingAnswerStreamItem> = flow {
        logger.debug(
            "Streaming answer synthesis for query: '{}', sources: {}, total facts: {}, previouslySearched: {}",
            input.query,
            input.evaluatedSources.size,
            input.evaluatedSources.sumOf { it.relevantFacts.size },
            input.previouslySearchedQueries.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId

        if (input.evaluatedSources.isEmpty() || input.evaluatedSources.all { it.relevantFacts.isEmpty() }) {
            logger.warn("No facts provided, emitting default message")
            emit(StreamingAnswerStreamItem.Chunk("No information found to answer the query."))
            emit(StreamingAnswerStreamItem.Complete(
                tokenUsage = TokenUsageMetrics.empty(modelId),
                reasoning = "No facts available from the provided sources.",
                citedSourceUrls = emptyList(),
                status = AnswerStatus.NEED_MORE_INFORMATION,
                followUpQueries = listOf(input.query)
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

        // Extract status, reasoning, followUpQueries, imageIds, and citedSourceUrls from the complete JSON
        val reasoning = extractReasoning(accumulatedJson)
        val citedSourceUrls = extractCitedSourceUrls(accumulatedJson)
        val status = extractStatus(accumulatedJson)
        val followUpQueries = extractFollowUpQueries(accumulatedJson)
        val imageIds = extractImageIds(accumulatedJson)
        
        logger.debug("Streaming answer synthesis complete: {} chars total, status: {}, {} images, citedSources: {}, followUpQueries: {}", 
            lastAnswerLength, status, imageIds.size, citedSourceUrls.size, followUpQueries.size)
        emit(StreamingAnswerStreamItem.Complete(
            tokenUsage = tokenUsage,
            reasoning = reasoning,
            citedSourceUrls = citedSourceUrls,
            status = status,
            followUpQueries = followUpQueries,
            imageIds = imageIds
        ))
    }

    /**
     * Extract status enum from accumulated JSON.
     * The JSON format is: {"reasoning": "...", "answer": "...", "status": "COMPLETE", ...}
     * Defaults to NEED_MORE_INFORMATION if not found or invalid.
     */
    private fun extractStatus(json: String): AnswerStatus {
        val regex = """"status"\s*:\s*"(COMPLETE|NEED_MORE_INFORMATION)"""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(json) ?: return AnswerStatus.NEED_MORE_INFORMATION
        return try {
            AnswerStatus.valueOf(match.groupValues[1].uppercase())
        } catch (e: IllegalArgumentException) {
            AnswerStatus.NEED_MORE_INFORMATION
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
     * Extract citedSourceUrls array from accumulated JSON.
     * The JSON format is: {"answer": "...", "citedSourceUrls": ["https://..."]}
     */
    private fun extractCitedSourceUrls(json: String): List<String> {
        val regex = """"citedSourceUrls"\s*:\s*\[([^\]]*)\]""".toRegex()
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
     * Extract followUpQueries array from accumulated JSON.
     * The JSON format is: {"answer": "...", "followUpQueries": ["query1", "query2"]}
     */
    private fun extractFollowUpQueries(json: String): List<String> {
        val regex = """"followUpQueries"\s*:\s*\[([^\]]*)\]""".toRegex()
        val match = regex.find(json) ?: return emptyList()
        
        val arrayContent = match.groupValues[1]
        if (arrayContent.isBlank()) return emptyList()
        
        // Extract individual string values from the array
        val stringRegex = """"((?:[^"\\]|\\.)*)"""".toRegex()
        return stringRegex.findAll(arrayContent)
            .map { unescapeJsonString(it.groupValues[1]) }
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
            // Static content first (for cache optimization)
            appendLine("# Extracted Facts from Sources")
            appendLine()

            input.evaluatedSources.forEachIndexed { index, source ->
                if (source.relevantFacts.isNotEmpty()) {
                    appendLine("## Source ${index + 1}")
                    appendLine("URL: ${source.url}")
                    appendLine("Source Classification: ${source.sourceClassification}")
                    appendLine("Content Date: ${source.contentDate ?: "Not found"}")
                    appendLine("Relevance: ${source.relevance}")
                    appendLine("Relevance Justification: ${source.relevanceReasoning}")
                    
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

            // Previously searched queries (for deduplication)
            if (input.previouslySearchedQueries.isNotEmpty()) {
                appendLine()
                appendLine("# Previously Searched Queries")
                appendLine("Do NOT suggest these as follow-up queries (they have already been searched):")
                input.previouslySearchedQueries.forEach { query ->
                    appendLine("- $query")
                }
                appendLine()
            }

            // Dynamic content at the end (query)
            appendLine()
            appendLine("# Query")
            appendLine(input.query)
            appendLine()

            appendLine("# Instructions")
            appendLine("Generate a comprehensive answer to the query using the extracted facts above.")
            appendLine("Prioritize facts from OFFICIAL_LIVING_DOC sources when synthesizing information.")
            appendLine("If relevant images are listed, include their IDs in the imageIds array.")
            appendLine("Decide if the answer is COMPLETE or needs more sources.")
        }
    }
}
