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
 * Uses a 5-dimension batch assessment to determine if sources are sufficient:
 * - COVERAGE: Facts address all parts of the query; multiple sources for negative conclusions
 * - DEPTH: Facts contain specific data (numbers, dates, prices) vs vague statements
 * - TEMPORALITY: Sources are recent enough for time-sensitive queries
 * - AUTHORITY: Sources are official/authoritative vs third-party/user-generated
 * - CONSISTENCY: Facts from different sources agree vs conflict
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
        .description("Assessment of a single source batch dimension")
        .properties(
            mapOf(
                "satisfied" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether this dimension is adequately met by the source batch")
                    .build(),
                "rationale" to Schema.builder()
                    .type("STRING")
                    .description("Brief explanation for the decision")
                    .build()
            )
        )
        .required(listOf("satisfied", "rationale"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Source batch assessment followed by answer synthesis")
        .properties(
            mapOf(
                "assessment" to Schema.builder()
                    .type("OBJECT")
                    .description("5-dimension assessment of whether the source batch is sufficient")
                    .properties(
                        mapOf(
                            "coverage" to dimensionAssessmentSchema,
                            "depth" to dimensionAssessmentSchema,
                            "temporality" to dimensionAssessmentSchema,
                            "authority" to dimensionAssessmentSchema,
                            "consistency" to dimensionAssessmentSchema
                        )
                    )
                    .required(listOf("coverage", "depth", "temporality", "authority", "consistency"))
                    .build(),
                "continuation_status" to Schema.builder()
                    .type("STRING")
                    .description("FINISH_SEARCH if confident answer found. CONTINUE_SEARCH if more information needed.")
                    .enum_(listOf("FINISH_SEARCH", "CONTINUE_SEARCH"))
                    .build(),
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Comprehensive answer based on the extracted facts")
                    .build(),
                "citedSourceUrls" to Schema.builder()
                    .type("ARRAY")
                    .description("URLs of sources whose facts were used in the answer")
                    .items(Schema.builder().type("STRING").build())
                    .build(),
                "followUpQueries" to Schema.builder()
                    .type("ARRAY")
                    .description("Suggested queries to gather more information and enhance the answer")
                    .items(Schema.builder().type("STRING").build())
                    .build(),
                "imageIds" to Schema.builder()
                    .type("ARRAY")
                    .description("Image IDs from sources to display with answer (e.g., '1', '2')")
                    .items(Schema.builder().type("STRING").build())
                    .build()
            )
        )
        .required(listOf("assessment", "continuation_status", "answer", "citedSourceUrls", "followUpQueries", "imageIds"))
        .build()

    private val systemInstruction = $$"""
        You synthesize answers from extracted facts. First assess if the source batch is sufficient, then generate the answer.

        ## Step 1: Assess Source Batch (5 Dimensions)
        
        Evaluate the collected facts. For each dimension, set satisfied=true/false.

        ### COVERAGE
        - Facts address all explicit and implicit parts of the query
        - Coverage is not satisfied if query has multiple parts but facts only cover some
        If "Fulfillment Requirements" are provided, evaluate each requirement:
        - satisfied=true ONLY if ALL listed requirements have adequate fact coverage
        - satisfied=false if ANY requirement lacks supporting facts
        - In the rationale, explicitly list which requirements are covered vs. uncovered
        - Example: "Requirements 1, 2 covered. Requirement 3 (enterprise pricing) NOT covered."

        ### DEPTH  
        - Facts contain specific data: numbers, prices, dates, versions, concrete details
        - The facts allow the formation of convincing deep answer
        - NOT satisfied: facts are vague without specifics, or are tangentially related, or can only serve as a surface level overview

        ### Temporality
        - Sources are recent enough for time-sensitive information
        - Satisfied: content is reasonably recent enough for the query, or topic is not time-sensitive
        - If you suspect the sources may be too old, set satisfied=false to eagerly allow further information gathering
        - Check "Content Date" field of each source if available

        ### AUTHORITY
        - Sources are credible
        - High credibility: official docs, product pages, pricing pages, Terms of Service etc.
        - Low credibility: blog posts, third-party sites, or user-generated content
        - Check "Intention" field of each source, authority requirement depends on the query

        ### CONSISTENCY
        - Facts from different sources agree
        - Satisfied: no conflicts, or conflicts resolved by preferring authoritative sources
        - NOT satisfied: sources contradict each other without clear resolution
        
        ## Step 2: Determine Search Continuation Status
        - Decide whether search should continue
        - Set continuation_status to CONTINUE_SEARCH or FINISH_SEARCH
        - Sources are continuously discovered. If you have any doubt, continue the search to allow for more information gathering.
        - If fulfillment requirements are provided and ANY requirement is not satisfied, set CONTINUE_SEARCH

        ## Step 3: Generate Answer
        
        - Synthesize facts into a comprehensive, standalone answer for answering the original query and the requirements
        - Use markdown formatting (headings, lists, bold)
        - Same language as the query
        - Only include information from provided facts
        - When facts conflict, note the conflicts and present as much information as possible

        ## Step 4: Generate Follow-up Queries
        
        - Suggest queries that could enhance or extend the current answer
        - Focus on related information that would make the answer more complete
        - These are independent of the assessment - even complete answers can have useful follow-ups
        - Check the "Follow-up queries" section for queries that have already been searched, do not duplicate queries
        
        ## Output Format
        {
            "assessment": {
                "coverage": { "satisfied": bool, "rationale": str },
                "depth": { "satisfied": bool, "rationale": str },
                "temporality": { "satisfied": bool, "rationale": str },
                "authority": { "satisfied": bool, "rationale": str },
                "consistency": { "satisfied": bool, "rationale": str }
            },
            "continuation_status": "FINISH_SEARCH" | "CONTINUE_SEARCH",
            "answer": "markdown answer",
            "citedSourceUrls": ["urls used"],
            "followUpQueries": ["queries to enhance answer"],
            "imageIds": ["1", "2"]
        }
    """.trimIndent()

    /**
     * Internal response data class for JSON deserialization.
     */
    @Serializable
    private data class LlmDimensionAssessment(
        val satisfied: Boolean,
        val rationale: String
    ) {
        fun toDomain(): DimensionAssessment = DimensionAssessment(
            satisfied = satisfied,
            rationale = rationale
        )
    }

    @Serializable
    private data class LlmAnswerAssessment(
        val coverage: LlmDimensionAssessment,
        val depth: LlmDimensionAssessment,
        val temporality: LlmDimensionAssessment,
        val authority: LlmDimensionAssessment,
        val consistency: LlmDimensionAssessment
    )

    @Serializable
    private data class SynthesisResponse(
        val answer: String,
        val citedSourceUrls: List<String> = emptyList(),
        val assessment: LlmAnswerAssessment,
        val continuation_status: AnswerStatus,
        val followUpQueries: List<String> = emptyList(),
        val imageIds: List<String> = emptyList()
    ) {
        /**
         * Converts internal LLM response types to domain types.
         */
        fun toAnswerAssessment(): AnswerAssessment = AnswerAssessment(
            coverage = assessment.coverage.toDomain(),
            depth = assessment.depth.toDomain(),
            temporality = assessment.temporality.toDomain(),
            authority = assessment.authority.toDomain(),
            consistency = assessment.consistency.toDomain()
        )
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
                coverage = DimensionAssessment(
                    satisfied = false,
                    rationale = "No facts available from the provided sources."
                ),
                depth = DimensionAssessment(satisfied = false, rationale = "No data available."),
                temporality = DimensionAssessment(satisfied = false, rationale = "No sources to evaluate."),
                authority = DimensionAssessment(satisfied = false, rationale = "No sources available."),
                consistency = DimensionAssessment(satisfied = false, rationale = "No sources to compare.")
            )
            return StreamingAnswerSynthesisOutput(
                answer = "No information found to answer the query.",
                citedSourceUrls = emptyList(),
                assessment = emptyAssessment,
                status = AnswerStatus.CONTINUE_SEARCH,
                followUpQueries = listOf(input.query),
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

        logger.debug(
            "Answer synthesis complete: {} chars, status: {}, {} images, citedSources: {}, followUpQueries: {}",
            response.answer.length,
            response.continuation_status,
            originalImageIds.size,
            response.citedSourceUrls.size,
            response.followUpQueries.size
        )

        return StreamingAnswerSynthesisOutput(
            answer = response.answer,
            citedSourceUrls = response.citedSourceUrls,
            assessment = answerAssessment,
            status = response.continuation_status,
            followUpQueries = response.followUpQueries,
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
                coverage = DimensionAssessment(
                    satisfied = false,
                    rationale = "No facts available from the provided sources."
                ),
                depth = DimensionAssessment(satisfied = false, rationale = "No data available."),
                temporality = DimensionAssessment(satisfied = false, rationale = "No sources to evaluate."),
                authority = DimensionAssessment(satisfied = false, rationale = "No sources available."),
                consistency = DimensionAssessment(satisfied = false, rationale = "No sources to compare.")
            )
            emit(StreamingAnswerStreamItem.Chunk("No information found to answer the query."))
            emit(StreamingAnswerStreamItem.Complete(
                tokenUsage = TokenUsageMetrics.empty(modelId),
                assessment = emptyAssessment,
                citedSourceUrls = emptyList(),
                status = AnswerStatus.CONTINUE_SEARCH,
                followUpQueries = listOf(input.query)
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

        // Extract assessment, status, citedSourceUrls, followUpQueries, and imageIds from the complete JSON
        val citedSourceUrls = extractCitedSourceUrls(accumulatedJson)
        val status = extractStatus(accumulatedJson)
        val followUpQueries = extractFollowUpQueries(accumulatedJson)
        val numberedImageIds = extractImageIds(accumulatedJson)
        val assessment = extractAssessment(accumulatedJson)
        
        // Map LLM-selected numbered image IDs back to original hash-based IDs
        val originalImageIds = mapSelectedImagesToOriginal(numberedImageIds, globalImages)
        
        logger.debug("Streaming answer synthesis complete: {} chars total, status: {}, {} images, citedSources: {}, followUpQueries: {}", 
            lastAnswerLength, status, originalImageIds.size, citedSourceUrls.size, followUpQueries.size)
        emit(StreamingAnswerStreamItem.Complete(
            tokenUsage = tokenUsage,
            assessment = assessment,
            citedSourceUrls = citedSourceUrls,
            status = status,
            followUpQueries = followUpQueries,
            imageIds = originalImageIds
        ))
    }

    /**
     * Extract continuation_status enum from accumulated JSON.
     * Defaults to CONTINUE_SEARCH if not found or invalid.
     */
    private fun extractStatus(json: String): AnswerStatus {
        val regex = """"continuation_status"\s*:\s*"(FINISH_SEARCH|CONTINUE_SEARCH)"""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(json) ?: return AnswerStatus.CONTINUE_SEARCH
        return try {
            AnswerStatus.valueOf(match.groupValues[1].uppercase())
        } catch (e: IllegalArgumentException) {
            AnswerStatus.CONTINUE_SEARCH
        }
    }

    /**
     * Extract followUpQueries array from accumulated JSON.
     */
    private fun extractFollowUpQueries(json: String): List<String> {
        val regex = """"followUpQueries"\s*:\s*\[([^\]]*)\]""".toRegex()
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
     * Parses the satisfied boolean and rationale string.
     */
    private fun extractDimensionAssessment(dimensionJson: String): DimensionAssessment {
        // Extract satisfied boolean
        val satisfiedRegex = """"satisfied"\s*:\s*(true|false)""".toRegex(RegexOption.IGNORE_CASE)
        val satisfied = satisfiedRegex.find(dimensionJson)?.groupValues?.get(1)?.lowercase() == "true"
        
        // Extract rationale string
        val rationaleRegex = """"rationale"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        val rationale = rationaleRegex.find(dimensionJson)?.groupValues?.get(1)?.let { unescapeJsonString(it) } ?: ""
        
        return DimensionAssessment(
            satisfied = satisfied,
            rationale = rationale
        )
    }

    /**
     * Extract the full 5-dimension assessment from accumulated JSON.
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
                rationale = "Failed to parse $dimensionName"
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
            coverage = extractDimension("coverage"),
            depth = extractDimension("depth"),
            temporality = extractDimension("temporality"),
            authority = extractDimension("authority"),
            consistency = extractDimension("consistency")
        )
    }

    /**
     * Creates a default unsatisfied assessment when parsing fails.
     */
    private fun createDefaultAssessment(): AnswerAssessment = AnswerAssessment(
        coverage = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment"),
        depth = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment"),
        temporality = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment"),
        authority = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment"),
        consistency = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment")
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
            appendLine("# Query")
            // Use expanded query if available, otherwise use original query
            appendLine(input.effectiveQuery)
            appendLine()
            
            // Include fulfillment requirements if available (critical for COVERAGE evaluation)
            if (input.fulfillmentRequirements.isNotEmpty()) {
                appendLine("# Fulfillment Requirements")
                input.fulfillmentRequirements.forEachIndexed { index, req ->
                    appendLine("${index + 1}. $req")
                }
                appendLine()
            }
            
            // Previously searched queries for follow-up deduplication
            if (input.previouslySearchedQueries.isNotEmpty()) {
                appendLine("# Follow-up queries")
                input.previouslySearchedQueries.forEach { query ->
                    appendLine("- $query")
                }
                appendLine()
            }
            
            appendLine("# Current Date")
            appendLine(java.time.LocalDate.now().toString())
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
            appendLine("# Current Date")
            appendLine(java.time.LocalDate.now().toString())
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
