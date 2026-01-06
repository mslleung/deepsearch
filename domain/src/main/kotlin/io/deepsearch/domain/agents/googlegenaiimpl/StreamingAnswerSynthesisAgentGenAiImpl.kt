package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.AnswerAssessment
import io.deepsearch.domain.agents.DimensionAssessment
import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.agents.StreamingAnswerSynthesisOutput
import io.deepsearch.domain.agents.StreamingAnswerStreamItem
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.flowWithRateLimitRetry
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.AnswerStatus
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
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
 * 
 * Uses a 4-dimension semantic assessment to determine answer quality:
 * - ANSWER_COMPLETENESS: All parts of the query are addressed
 * - ANSWER_DEPTH: Answer contains specific data vs generic statements
 * - QUERY_INTENTION_FULFILLMENT: User would not need to search more
 * - SOURCE_CONFIDENCE: Sources are authoritative and recent
 */
class StreamingAnswerSynthesisAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IStreamingAnswerSynthesisAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Schema for a single dimension assessment.
     */
    private val dimensionAssessmentSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Assessment of a single quality dimension")
        .properties(
            mapOf(
                "satisfied" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether this dimension is adequately addressed")
                    .build(),
                "rationale" to Schema.builder()
                    .type("STRING")
                    .description("Brief explanation for the decision")
                    .build(),
                "followUpQueries" to Schema.builder()
                    .type("ARRAY")
                    .description("Targeted queries to improve this dimension (empty if satisfied)")
                    .items(Schema.builder().type("STRING").build())
                    .build()
            )
        )
        .required(listOf("satisfied", "rationale", "followUpQueries"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Comprehensive answer with 4-dimension quality assessment")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Comprehensive answer to the search query based on the extracted facts")
                    .build(),
                "citedSourceUrls" to Schema.builder()
                    .type("ARRAY")
                    .description("URLs of sources whose facts were actually used/cited in generating the answer. Only include sources that contributed to the answer content.")
                    .items(Schema.builder().type("STRING").build())
                    .build(),
                "assessment" to Schema.builder()
                    .type("OBJECT")
                    .description("4-dimension quality assessment of the answer")
                    .properties(
                        mapOf(
                            "answerCompleteness" to dimensionAssessmentSchema,
                            "answerDepth" to dimensionAssessmentSchema,
                            "queryIntentionFulfillment" to dimensionAssessmentSchema,
                            "sourceConfidence" to dimensionAssessmentSchema
                        )
                    )
                    .required(listOf("answerCompleteness", "answerDepth", "queryIntentionFulfillment", "sourceConfidence"))
                    .build(),
                "status" to Schema.builder()
                    .type("STRING")
                    .description("COMPLETE only if ALL 4 dimensions are satisfied. NEED_MORE_INFORMATION if ANY dimension is not satisfied.")
                    .enum_(listOf("COMPLETE", "NEED_MORE_INFORMATION"))
                    .build(),
                "imageIds" to Schema.builder()
                    .type("ARRAY")
                    .description("List of image IDs (numbered, e.g., '1', '2', '3') from the sources that should be displayed with the answer. Select from the numbered image IDs listed under 'Relevant Images' for each source.")
                    .items(Schema.builder().type("STRING").build())
                    .build()
            )
        )
        .required(listOf("answer", "citedSourceUrls", "assessment", "status", "imageIds"))
        .build()

    private val systemInstruction = $$"""
        You are an answer synthesis agent that generates comprehensive answers from pre-extracted facts.
        You are part of a feedback loop that continues searching until the answer is complete.

        ## Step 1: Synthesize the Answer
        - Generate a comprehensive answer based on the provided facts
        - The answer should be standalone and directly address the user query
        - Only include information present in the provided facts - do not invent
        - Use markdown styling as applicable (headings, lists, bold, etc.)
        - The answer should be in the same language as the input query
        - When facts conflict, prefer information from official sources (check the "Intention" field)
        
        ## Step 2: Assess Answer Quality (4 Dimensions)
        
        After writing the answer, step back and critically evaluate what you wrote.
        Pretend you are a skeptical user who received this answer.
        
        For EACH dimension, determine if it is SATISFIED or NOT_SATISFIED:

        ### ANSWER_COMPLETENESS (answerCompleteness)
        - **SATISFIED**: All explicit and implicit parts of the query are addressed
        - **NOT_SATISFIED**: Some parts of the query remain unanswered
        - If NOT_SATISFIED, provide followUpQueries to fill the gaps

        ### ANSWER_DEPTH (answerDepth)
        - **SATISFIED**: Answer contains specific data (numbers, dates, concrete details, prices, versions)
        - **NOT_SATISFIED**: Answer is generic/vague without specifics (e.g., "pricing available" without actual prices)
        - If NOT_SATISFIED, provide followUpQueries to get specific data

        ### QUERY_INTENTION_FULFILLMENT (queryIntentionFulfillment)
        - **SATISFIED**: User has actionable information, no follow-up search needed
        - **NOT_SATISFIED**: User would still need to search more to act on this information
        - If NOT_SATISFIED, provide followUpQueries for what user would need next

        ### SOURCE_CONFIDENCE (sourceConfidence)
        - **SATISFIED**: Sources are official docs, pricing pages, or recent (<6 months old) official content
        - **NOT_SATISFIED**: Sources are old blog posts (>6 months), third-party content, or user-generated content
        - Check the "Content Date" and "Intention" fields of each source
        - If NOT_SATISFIED, provide followUpQueries to find more authoritative sources
        
        ## Step 3: Status Decision
        - **COMPLETE**: Use ONLY when ALL 4 dimensions are SATISFIED
        - **NEED_MORE_INFORMATION**: Use when ANY dimension is NOT_SATISFIED
        
        The rule is simple: if any dimension has satisfied=false → status MUST be NEED_MORE_INFORMATION
        
        ## Citation Source URLs
        - List the URLs of sources whose facts you actually used in generating the answer
        - Only include sources that contributed information to the answer
        
        Expected Output:
        {
            "answer": "your answer text with markdown formatting",
            "citedSourceUrls": ["https://..."],
            "assessment": {
                "answerCompleteness": {
                    "satisfied": true/false,
                    "rationale": string,
                    "followUpQueries": [string]
                },
                "answerDepth": {
                    "satisfied": true/false,
                    "rationale": string,
                    "followUpQueries": [string]
                },
                "queryIntentionFulfillment": {
                    "satisfied": true/false,
                    "rationale": string,
                    "followUpQueries": [string]
                },
                "sourceConfidence": {
                    "satisfied": true/false,
                    "rationale": string,
                    "followUpQueries": [string]
                }
            },
            "status": "COMPLETE"/"NEED_MORE_INFORMATION",
            "imageIds": ["1", "5", "7"]
        }
    """.trimIndent()

    /**
     * Internal response data class for JSON deserialization.
     */
    @Serializable
    private data class LlmDimensionAssessment(
        val satisfied: Boolean,
        val rationale: String,
        val followUpQueries: List<String> = emptyList()
    ) {
        fun toDomain(): DimensionAssessment = DimensionAssessment(
            satisfied = satisfied,
            rationale = rationale,
            followUpQueries = followUpQueries
        )
    }

    @Serializable
    private data class LlmAnswerAssessment(
        val answerCompleteness: LlmDimensionAssessment,
        val answerDepth: LlmDimensionAssessment,
        val queryIntentionFulfillment: LlmDimensionAssessment,
        val sourceConfidence: LlmDimensionAssessment
    )

    @Serializable
    private data class SynthesisResponse(
        val answer: String,
        val citedSourceUrls: List<String> = emptyList(),
        val assessment: LlmAnswerAssessment,
        val status: AnswerStatus,
        val imageIds: List<String> = emptyList()
    ) {
        /**
         * Converts internal LLM response types to domain types.
         */
        fun toAnswerAssessment(): AnswerAssessment = AnswerAssessment(
            answerCompleteness = assessment.answerCompleteness.toDomain(),
            answerDepth = assessment.answerDepth.toDomain(),
            queryIntentionFulfillment = assessment.queryIntentionFulfillment.toDomain(),
            sourceConfidence = assessment.sourceConfidence.toDomain()
        )

        /**
         * Collects all follow-up queries from unsatisfied dimensions.
         */
        fun allFollowUpQueries(): List<String> = listOf(
            assessment.answerCompleteness.followUpQueries,
            assessment.answerDepth.followUpQueries,
            assessment.queryIntentionFulfillment.followUpQueries,
            assessment.sourceConfidence.followUpQueries
        ).flatten()
    }

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
            val emptyAssessment = AnswerAssessment(
                answerCompleteness = DimensionAssessment(
                    satisfied = false,
                    rationale = "No facts available from the provided sources.",
                    followUpQueries = listOf(input.query)
                ),
                answerDepth = DimensionAssessment(satisfied = false, rationale = "No data available.", followUpQueries = emptyList()),
                queryIntentionFulfillment = DimensionAssessment(satisfied = false, rationale = "Query cannot be answered.", followUpQueries = emptyList()),
                sourceConfidence = DimensionAssessment(satisfied = false, rationale = "No sources available.", followUpQueries = emptyList())
            )
            return StreamingAnswerSynthesisOutput(
                answer = "No information found to answer the query.",
                citedSourceUrls = emptyList(),
                assessment = emptyAssessment,
                status = AnswerStatus.NEED_MORE_INFORMATION,
                tokenUsage = tokenUsage
            )
        }

        // Collect all unique images across sources into a global ordered list
        val globalImages = collectGlobalImages(input.evaluatedSources)

        val userPrompt = buildUserPrompt(input, globalImages)

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

        // Map LLM-selected numbered image IDs back to original hash-based IDs
        val originalImageIds = mapSelectedImagesToOriginal(response.imageIds, globalImages)

        val answerAssessment = response.toAnswerAssessment()
        val followUpQueries = response.allFollowUpQueries()

        logger.debug(
            "Answer synthesis complete: {} chars, status: {}, {} images, citedSources: {}, followUpQueries: {}",
            response.answer.length,
            response.status,
            originalImageIds.size,
            response.citedSourceUrls.size,
            followUpQueries.size
        )

        return StreamingAnswerSynthesisOutput(
            answer = response.answer,
            citedSourceUrls = response.citedSourceUrls,
            assessment = answerAssessment,
            status = response.status,
            imageIds = originalImageIds,
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
            val emptyAssessment = AnswerAssessment(
                answerCompleteness = DimensionAssessment(
                    satisfied = false,
                    rationale = "No facts available from the provided sources.",
                    followUpQueries = listOf(input.query)
                ),
                answerDepth = DimensionAssessment(satisfied = false, rationale = "No data available.", followUpQueries = emptyList()),
                queryIntentionFulfillment = DimensionAssessment(satisfied = false, rationale = "Query cannot be answered.", followUpQueries = emptyList()),
                sourceConfidence = DimensionAssessment(satisfied = false, rationale = "No sources available.", followUpQueries = emptyList())
            )
            emit(StreamingAnswerStreamItem.Chunk("No information found to answer the query."))
            emit(StreamingAnswerStreamItem.Complete(
                tokenUsage = TokenUsageMetrics.empty(modelId),
                assessment = emptyAssessment,
                citedSourceUrls = emptyList(),
                status = AnswerStatus.NEED_MORE_INFORMATION
            ))
            return@flow
        }

        // Collect all unique images across sources into a global ordered list
        val globalImages = collectGlobalImages(input.evaluatedSources)

        val userPrompt = buildUserPrompt(input, globalImages)

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

        // Extract assessment, status, citedSourceUrls, and imageIds from the complete JSON
        val citedSourceUrls = extractCitedSourceUrls(accumulatedJson)
        val status = extractStatus(accumulatedJson)
        val numberedImageIds = extractImageIds(accumulatedJson)
        val assessment = extractAssessment(accumulatedJson)
        val followUpQueries = assessment.allFollowUpQueries()
        
        // Map LLM-selected numbered image IDs back to original hash-based IDs
        val originalImageIds = mapSelectedImagesToOriginal(numberedImageIds, globalImages)
        
        logger.debug("Streaming answer synthesis complete: {} chars total, status: {}, {} images, citedSources: {}, followUpQueries: {}", 
            lastAnswerLength, status, originalImageIds.size, citedSourceUrls.size, followUpQueries.size)
        emit(StreamingAnswerStreamItem.Complete(
            tokenUsage = tokenUsage,
            assessment = assessment,
            citedSourceUrls = citedSourceUrls,
            status = status,
            imageIds = originalImageIds
        ))
    }

    /**
     * Extract status enum from accumulated JSON.
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
     * Extract imageIds array from accumulated JSON.
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
     * Extract a single dimension assessment from JSON.
     * Parses the satisfied boolean, rationale string, and followUpQueries array.
     */
    private fun extractDimensionAssessment(dimensionJson: String): DimensionAssessment {
        // Extract satisfied boolean
        val satisfiedRegex = """"satisfied"\s*:\s*(true|false)""".toRegex(RegexOption.IGNORE_CASE)
        val satisfied = satisfiedRegex.find(dimensionJson)?.groupValues?.get(1)?.lowercase() == "true"
        
        // Extract rationale string
        val rationaleRegex = """"rationale"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        val rationale = rationaleRegex.find(dimensionJson)?.groupValues?.get(1)?.let { unescapeJsonString(it) } ?: ""
        
        // Extract followUpQueries array
        val queriesRegex = """"followUpQueries"\s*:\s*\[([^\]]*)\]""".toRegex()
        val queriesMatch = queriesRegex.find(dimensionJson)
        val followUpQueries = if (queriesMatch != null) {
            val arrayContent = queriesMatch.groupValues[1]
            if (arrayContent.isBlank()) {
                emptyList()
            } else {
                val stringRegex = """"([^"]+)"""".toRegex()
                stringRegex.findAll(arrayContent).map { it.groupValues[1] }.toList()
            }
        } else {
            emptyList()
        }
        
        return DimensionAssessment(
            satisfied = satisfied,
            rationale = rationale,
            followUpQueries = followUpQueries
        )
    }

    /**
     * Extract the full 4-dimension assessment from accumulated JSON.
     * Falls back to unsatisfied dimensions if parsing fails.
     */
    private fun extractAssessment(json: String): AnswerAssessment {
        // Find the assessment object in JSON
        val assessmentRegex = """"assessment"\s*:\s*\{""".toRegex()
        val assessmentStart = assessmentRegex.find(json)?.range?.last ?: return createDefaultAssessment()
        
        // Find matching closing brace by counting braces
        var braceCount = 1
        var endIndex = assessmentStart + 1
        while (endIndex < json.length && braceCount > 0) {
            when (json[endIndex]) {
                '{' -> braceCount++
                '}' -> braceCount--
            }
            endIndex++
        }
        
        val assessmentJson = json.substring(assessmentStart, endIndex)
        
        // Extract each dimension
        fun extractDimension(dimensionName: String): DimensionAssessment {
            val dimRegex = """"$dimensionName"\s*:\s*\{""".toRegex()
            val dimStart = dimRegex.find(assessmentJson)?.range?.last ?: return DimensionAssessment(
                satisfied = false,
                rationale = "Failed to parse $dimensionName",
                followUpQueries = emptyList()
            )
            
            // Find matching closing brace
            var count = 1
            var dimEnd = dimStart + 1
            while (dimEnd < assessmentJson.length && count > 0) {
                when (assessmentJson[dimEnd]) {
                    '{' -> count++
                    '}' -> count--
                }
                dimEnd++
            }
            
            val dimensionJson = assessmentJson.substring(dimStart, dimEnd)
            return extractDimensionAssessment(dimensionJson)
        }
        
        return AnswerAssessment(
            answerCompleteness = extractDimension("answerCompleteness"),
            answerDepth = extractDimension("answerDepth"),
            queryIntentionFulfillment = extractDimension("queryIntentionFulfillment"),
            sourceConfidence = extractDimension("sourceConfidence")
        )
    }

    /**
     * Creates a default unsatisfied assessment when parsing fails.
     */
    private fun createDefaultAssessment(): AnswerAssessment = AnswerAssessment(
        answerCompleteness = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment", followUpQueries = emptyList()),
        answerDepth = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment", followUpQueries = emptyList()),
        queryIntentionFulfillment = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment", followUpQueries = emptyList()),
        sourceConfidence = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment", followUpQueries = emptyList())
    )

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

    private fun buildUserPrompt(
        input: StreamingAnswerSynthesisInput,
        globalImages: List<GlobalImage>
    ): String {
        // Build a lookup from original image ID to global numbered ID
        val imageIdToNumbered = globalImages.withIndex()
            .associate { (index, img) -> img.originalId to (index + 1).toString() }
        
        return buildString {
            // Static content first (for cache optimization)
            appendLine("# Extracted Facts from Sources")
            appendLine()

            input.evaluatedSources.forEachIndexed { index, source ->
                if (source.relevantFacts.isNotEmpty()) {
                    appendLine("## Source ${index + 1}")
                    appendLine("URL: ${source.url}")
                    appendLine("Intention: ${source.intention}")
                    appendLine("Content Date: ${source.contentDate ?: "Not found"}")

                    appendLine()
                    appendLine("### Facts")
                    source.relevantFacts.forEach { fact ->
                        appendLine("- ${fact.fact}")
                    }

                    // Show available images for this source with descriptions from input
                    val sourceImages = source.relevantImageIds.mapNotNull { imageId ->
                        val numberedId = imageIdToNumbered[imageId]
                        val description = input.imageDescriptions[imageId]
                        if (numberedId != null && description != null) {
                            numberedId to description
                        } else null
                    }
                    if (sourceImages.isNotEmpty()) {
                        appendLine()
                        appendLine("### Available Images")
                        sourceImages.forEach { (numberedId, description) ->
                            appendLine("- Image $numberedId: $description")
                        }
                    }
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }

            // Dynamic content at the end (query)
            appendLine()
            appendLine("# Query")
            appendLine(input.query)
        }
    }

    /**
     * Represents an image collected from all sources with a global numbered ID.
     * The numbered ID is derived from the position in the list (index + 1).
     * 
     * @property originalId The original image ID (e.g., "img-abc123")
     */
    private data class GlobalImage(
        val originalId: String
    )

    /**
     * Collects all unique image IDs from evaluated sources into a global ordered list.
     * Each image gets a sequential numbered ID (1, 2, 3...) based on position.
     *
     * @param sources List of evaluated sources containing image IDs
     * @return Ordered list of global images (index + 1 = numbered ID)
     */
    private fun collectGlobalImages(
        sources: List<EvaluatedSource>
    ): List<GlobalImage> {
        val seenIds = mutableSetOf<String>()
        val globalImages = mutableListOf<GlobalImage>()

        sources.forEach { source ->
            source.relevantImageIds.forEach { imageId ->
                if (imageId !in seenIds) {
                    seenIds.add(imageId)
                    globalImages.add(GlobalImage(originalId = imageId))
                }
            }
        }
        return globalImages
    }

    /**
     * Maps LLM-selected numbered image IDs back to original hash-based IDs.
     *
     * @param selectedNumberedIds List of numbered IDs selected by the LLM (e.g., ["1", "3"])
     * @param globalImages Ordered list of global images
     * @return List of original hash-based image IDs
     */
    private fun mapSelectedImagesToOriginal(
        selectedNumberedIds: List<String>,
        globalImages: List<GlobalImage>
    ): List<String> {
        return selectedNumberedIds.mapNotNull { numberedId ->
            val index = numberedId.toIntOrNull()?.minus(1) ?: return@mapNotNull null
            globalImages.getOrNull(index)?.originalId
        }
    }
}
