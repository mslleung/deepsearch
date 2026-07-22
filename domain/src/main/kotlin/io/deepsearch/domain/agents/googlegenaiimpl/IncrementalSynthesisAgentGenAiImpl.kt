package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.AnswerAssessment
import io.deepsearch.domain.agents.DimensionAssessment
import io.deepsearch.domain.agents.IIncrementalSynthesisAgent
import io.deepsearch.domain.agents.IncrementalSynthesisInput
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
 * Incremental synthesis agent that updates an existing answer with newly discovered sources.
 * Instead of re-reading all sources, it receives only new sources + the current answer state
 * and decides whether/how to update.
 */
class IncrementalSynthesisAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IIncrementalSynthesisAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val dimensionAssessmentSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Assessment of a single source batch dimension")
        .properties(
            mapOf(
                "satisfied" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether this dimension is adequately met")
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
        .description("Incremental answer update with assessment")
        .properties(
            mapOf(
                "assessment" to Schema.builder()
                    .type("OBJECT")
                    .description("5-dimension assessment considering existing + new information")
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
                    .description("FINISH_SEARCH if answer is complete. CONTINUE_SEARCH if more needed.")
                    .enum_(listOf("FINISH_SEARCH", "CONTINUE_SEARCH"))
                    .build(),
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Complete updated answer (not a diff)")
                    .build(),
                "citedSourceUrls" to Schema.builder()
                    .type("ARRAY")
                    .description("ALL URLs cited in the answer (existing + new)")
                    .items(Schema.builder().type("STRING").build())
                    .build(),
                "followUpQueries" to Schema.builder()
                    .type("ARRAY")
                    .description("Suggested queries to gather more information")
                    .items(Schema.builder().type("STRING").build())
                    .build(),
                "refinedRequirements" to Schema.builder()
                    .type("ARRAY")
                    .description("Updated fulfillment requirements (max 10)")
                    .items(Schema.builder().type("STRING").build())
                    .build(),
                "imageIds" to Schema.builder()
                    .type("ARRAY")
                    .description("Image IDs from sources to display with answer")
                    .items(Schema.builder().type("STRING").build())
                    .build()
            )
        )
        .required(listOf("assessment", "continuation_status", "answer", "citedSourceUrls", "followUpQueries", "refinedRequirements", "imageIds"))
        .build()

    private val systemInstruction = $$"""
        Your task is to update an existing answer with newly discovered sources. First evaluate whether the new sources add value, then update the answer and re-assess quality.

        ## Session Continuation (if "Prior Session Findings" is provided)
        - Use the prior conversation as context
        - Focus on providing the most appropriate/informative answer as a continuation of the session

        ## Step 1: Evaluate New Sources Against Current Answer
        
        For each new source, classify it as one of:
        - NEW: Contains information not present in the current answer
        - CONTRADICTORY: Conflicts with information in the current answer
        - DEEPENING: Provides more specific data (numbers, dates, prices) for something covered vaguely
        - REDUNDANT: Already covered by the current answer

        If ALL new sources are REDUNDANT, return the current answer UNCHANGED with the current assessment and set continuation_status based on the current assessment state.

        ## Step 2: Update the Answer
        - Produce the COMPLETE updated answer (not a diff) incorporating useful new information
        - Preserve all existing citations [1],[2],... from the current answer
        - Add new citations starting from the next available number (provided in "Current Citations")
        - Integrate new information naturally into the existing structure
        - Eagerly provide as much information as possible
        - Use markdown formatting (headings, lists, bold)
        - Same language as the query
        - Only include information from provided facts, do not invent
        - When facts conflict, note the conflicts and present as much information as possible
        - citedSourceUrls must include ALL URLs cited in the answer (both existing and new)

        ## Step 3: Re-assess Source Quality (5 Dimensions)

        Evaluate the quality of the combined answer (existing + newly incorporated information). For each dimension, set satisfied=true/false.

        ### COVERAGE
        - Evaluate against fulfillment requirements
        - Coverage is satisfied when ALL requirements have adequate fact coverage
        - In the rationale, explicitly list which requirements are covered vs. uncovered
        - Example: "Requirements 1, 2 covered. Requirement 3 (enterprise pricing) NOT covered."

        ### DEPTH
        - Facts contain specific data: numbers, prices, dates, versions, concrete details
        - The facts allow the formation of a convincing deep answer
        - NOT satisfied: facts are vague without specifics, or are tangentially related, or can only serve as a surface level overview

        ### TEMPORALITY
        - Sources are recent enough for time-sensitive information
        - Satisfied: content is reasonably recent enough for the query, or topic is not time-sensitive
        - If you suspect the sources may be too old, set satisfied=false to eagerly allow further information gathering
        - Check "Content Date" field of each source if available

        ### AUTHORITY
        - All sources come from the official website URL, assess authority based on the intention purpose of the webpage
        - High credibility: official docs, product pages, pricing pages, Terms of Service etc.
        - Low credibility: blog posts, press-release
        - Low credibility sources can still be used, authority matters in conflict resolution

        ### CONSISTENCY
        - Facts from different sources agree
        - Satisfied: no conflicts, or conflicts resolved by preferring authoritative sources
        - NOT satisfied: sources contradict each other without clear resolution

        ## Step 4: Determine Search Continuation Status
        - FINISH_SEARCH: The answer addresses the core query with specific, authoritative data. All requirements are satisfied.
        - CONTINUE_SEARCH: Critical information is still missing or sources are insufficient.

        ## Step 5: Generate Follow-up Queries
        - Only suggest follow-ups when continuation_status is CONTINUE_SEARCH
        - Focus on missing requirements
        - CRITICAL: Check "Previously searched queries" section - NEVER suggest any query that appears there or is semantically equivalent

        ## Step 6: Refine Requirements
        - Based on what you learned from the new sources, update the fulfillment requirements to guide subsequent search direction
        - Keep the list of requirements small
        - Each requirement must be atomic and verifiable
        - Always preserve the core intent of the original query
        - If FINISH_SEARCH, output the current requirements unchanged

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
            "answer": "complete updated markdown answer",
            "citedSourceUrls": ["all urls cited in the answer"],
            "followUpQueries": ["queries to enhance answer"],
            "refinedRequirements": ["updated requirements for next iteration"],
            "imageIds": ["1", "2"]
        }
    """.trimIndent()

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
        val refinedRequirements: List<String> = emptyList(),
        val imageIds: List<String> = emptyList()
    ) {
        fun toAnswerAssessment(): AnswerAssessment = AnswerAssessment(
            coverage = assessment.coverage.toDomain(),
            depth = assessment.depth.toDomain(),
            temporality = assessment.temporality.toDomain(),
            authority = assessment.authority.toDomain(),
            consistency = assessment.consistency.toDomain()
        )
    }

    override suspend fun generate(input: IncrementalSynthesisInput): StreamingAnswerSynthesisOutput {
        logger.debug(
            "Incremental synthesis: query='{}', newSources={}, currentAnswerLength={}, existingCitations={}",
            input.query,
            input.newSources.size,
            input.currentAnswer.length,
            input.currentCitedSourceUrls.size
        )

        val modelId = ModelIds.GEMINI_3_5_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.newSources.isEmpty() || input.newSources.all { it.relevantFacts.isEmpty() }) {
            logger.warn("No new facts provided, returning current answer unchanged")
            return StreamingAnswerSynthesisOutput(
                answer = input.currentAnswer,
                citedSourceUrls = input.currentCitedSourceUrls,
                assessment = input.currentAssessment,
                status = AnswerStatus.CONTINUE_SEARCH,
                followUpQueries = emptyList(),
                tokenUsage = tokenUsage
            )
        }

        val globalImages = collectGlobalImages(input.newSources)
        val userPrompt = buildUserPrompt(input, globalImages)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<SynthesisResponse>(this@IncrementalSynthesisAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    userPrompt,
                    GenerateContentConfig.builder()
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

        logger.debug("[{}] response: {}", IncrementalSynthesisAgentGenAiImpl::class.simpleName, response)

        val originalImageIds = mapSelectedImagesToOriginal(response.imageIds, globalImages)
        val answerAssessment = response.toAnswerAssessment()

        val dedupedFollowUpQueries = deduplicateFollowUpQueries(
            response.followUpQueries,
            input.previouslySearchedQueries
        )

        logger.debug(
            "Incremental synthesis complete: {} chars, status={}, citedSources={}, followUpQueries={} (deduped from {})",
            response.answer.length,
            response.continuation_status,
            response.citedSourceUrls.size,
            dedupedFollowUpQueries.size,
            response.followUpQueries.size
        )

        return StreamingAnswerSynthesisOutput(
            answer = response.answer,
            citedSourceUrls = response.citedSourceUrls,
            assessment = answerAssessment,
            status = response.continuation_status,
            followUpQueries = dedupedFollowUpQueries,
            refinedRequirements = response.refinedRequirements,
            imageIds = originalImageIds,
            tokenUsage = tokenUsage
        )
    }

    override fun generateStream(input: IncrementalSynthesisInput): Flow<StreamingAnswerStreamItem> = flow {
        logger.debug(
            "Streaming incremental synthesis: query='{}', newSources={}, currentAnswerLength={}",
            input.query,
            input.newSources.size,
            input.currentAnswer.length
        )

        val modelId = ModelIds.GEMINI_3_5_FLASH_LITE.modelId

        if (input.newSources.isEmpty() || input.newSources.all { it.relevantFacts.isEmpty() }) {
            logger.warn("No new facts provided, emitting current answer unchanged")
            emit(StreamingAnswerStreamItem.Chunk(input.currentAnswer))
            emit(StreamingAnswerStreamItem.Complete(
                tokenUsage = TokenUsageMetrics.empty(modelId),
                assessment = input.currentAssessment,
                citedSourceUrls = input.currentCitedSourceUrls,
                status = AnswerStatus.CONTINUE_SEARCH,
                followUpQueries = emptyList()
            ))
            return@flow
        }

        val globalImages = collectGlobalImages(input.newSources)
        val userPrompt = buildUserPrompt(input, globalImages)

        val config = GenerateContentConfig.builder()
            .responseSchema(outputSchema)
            .responseMimeType("application/json")
            .thinkingConfig(
                ThinkingConfig.builder()
                    .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                    .build()
            )
            .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
            .build()

        var accumulatedJson = ""
        var lastAnswerLength = 0
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        flowWithRateLimitRetry(this@IncrementalSynthesisAgentGenAiImpl::class.simpleName!!) {
            accumulatedJson = ""
            lastAnswerLength = 0
            tokenUsage = TokenUsageMetrics.empty(modelId)
            client.models.generateContentStream(modelId, userPrompt, config)
        }.flowOn(dispatcherProvider.io).collect { response ->
            val chunkText = response.text() ?: return@collect
            accumulatedJson += chunkText

            val answerDelta = extractAnswerDelta(accumulatedJson, lastAnswerLength)
            if (answerDelta.isNotEmpty()) {
                lastAnswerLength += answerDelta.length
                emit(StreamingAnswerStreamItem.Chunk(answerDelta))
            }

            response.usageMetadata().ifPresent { metadata ->
                val promptTokens = metadata.promptTokenCount().orElse(0)
                val outputTokens = metadata.candidatesTokenCount().orElse(0)
                val totalTokens = metadata.totalTokenCount().orElse(0)
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

        logger.debug("[{}] response: {}", IncrementalSynthesisAgentGenAiImpl::class.simpleName, accumulatedJson)

        val citedSourceUrls = extractCitedSourceUrls(accumulatedJson)
        val status = extractStatus(accumulatedJson)
        val rawFollowUpQueries = extractFollowUpQueries(accumulatedJson)
        val refinedRequirements = extractRefinedRequirements(accumulatedJson)
        val numberedImageIds = extractImageIds(accumulatedJson)
        val assessment = extractAssessment(accumulatedJson)

        val followUpQueries = deduplicateFollowUpQueries(
            rawFollowUpQueries,
            input.previouslySearchedQueries
        )

        val originalImageIds = mapSelectedImagesToOriginal(numberedImageIds, globalImages)

        logger.debug(
            "Streaming incremental synthesis complete: {} chars, status={}, citedSources={}, followUpQueries={}",
            lastAnswerLength, status, citedSourceUrls.size, followUpQueries.size
        )
        emit(StreamingAnswerStreamItem.Complete(
            tokenUsage = tokenUsage,
            assessment = assessment,
            citedSourceUrls = citedSourceUrls,
            status = status,
            followUpQueries = followUpQueries,
            refinedRequirements = refinedRequirements,
            imageIds = originalImageIds
        ))
    }

    private fun extractStatus(json: String): AnswerStatus {
        val regex = """"continuation_status"\s*:\s*"(FINISH_SEARCH|CONTINUE_SEARCH)"""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(json) ?: return AnswerStatus.CONTINUE_SEARCH
        return AnswerStatus.valueOf(match.groupValues[1].uppercase())
    }

    private fun extractFollowUpQueries(json: String): List<String> {
        val regex = """"followUpQueries"\s*:\s*\[([^\]]*)\]""".toRegex()
        val match = regex.find(json) ?: return emptyList()
        val arrayContent = match.groupValues[1]
        if (arrayContent.isBlank()) return emptyList()
        val stringRegex = """"([^"]+)"""".toRegex()
        return stringRegex.findAll(arrayContent).map { it.groupValues[1] }.toList()
    }

    private fun deduplicateFollowUpQueries(
        followUpQueries: List<String>,
        previouslySearchedQueries: List<String>
    ): List<String> {
        if (previouslySearchedQueries.isEmpty()) return followUpQueries
        val previousLowercase = previouslySearchedQueries.map { it.lowercase().trim() }
        return followUpQueries.filter { query ->
            val queryLower = query.lowercase().trim()
            previousLowercase.none { prev ->
                queryLower == prev || queryLower.contains(prev) || prev.contains(queryLower)
            }
        }
    }

    private fun extractRefinedRequirements(json: String): List<String> {
        val regex = """"refinedRequirements"\s*:\s*\[([^\]]*)\]""".toRegex()
        val match = regex.find(json) ?: return emptyList()
        val arrayContent = match.groupValues[1]
        if (arrayContent.isBlank()) return emptyList()
        val stringRegex = """"([^"]+)"""".toRegex()
        return stringRegex.findAll(arrayContent).map { it.groupValues[1] }.toList()
    }

    private fun extractImageIds(json: String): List<String> {
        val regex = """"imageIds"\s*:\s*\[([^\]]*)\]""".toRegex()
        val match = regex.find(json) ?: return emptyList()
        val arrayContent = match.groupValues[1]
        if (arrayContent.isBlank()) return emptyList()
        val stringRegex = """"([^"]+)"""".toRegex()
        return stringRegex.findAll(arrayContent).map { it.groupValues[1] }.toList()
    }

    private fun extractCitedSourceUrls(json: String): List<String> {
        val regex = """"citedSourceUrls"\s*:\s*\[([^\]]*)\]""".toRegex()
        val match = regex.find(json) ?: return emptyList()
        val arrayContent = match.groupValues[1]
        if (arrayContent.isBlank()) return emptyList()
        val stringRegex = """"([^"]+)"""".toRegex()
        return stringRegex.findAll(arrayContent).map { it.groupValues[1] }.toList()
    }

    private fun extractDimensionAssessment(dimensionJson: String): DimensionAssessment {
        val satisfiedRegex = """"satisfied"\s*:\s*(true|false)""".toRegex(RegexOption.IGNORE_CASE)
        val satisfied = satisfiedRegex.find(dimensionJson)?.groupValues?.get(1)?.lowercase() == "true"
        val rationaleRegex = """"rationale"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        val rationale = rationaleRegex.find(dimensionJson)?.groupValues?.get(1)?.let { unescapeJsonString(it) } ?: ""
        return DimensionAssessment(satisfied = satisfied, rationale = rationale)
    }

    private fun extractAssessment(json: String): AnswerAssessment {
        val assessmentRegex = """"assessment"\s*:\s*\{""".toRegex()
        val assessmentStart = assessmentRegex.find(json)?.range?.last ?: return createDefaultAssessment()

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

        fun extractDimension(dimensionName: String): DimensionAssessment {
            val dimRegex = """"$dimensionName"\s*:\s*\{""".toRegex()
            val dimStart = dimRegex.find(assessmentJson)?.range?.last ?: return DimensionAssessment(
                satisfied = false, rationale = "Failed to parse $dimensionName"
            )
            var count = 1
            var dimEnd = dimStart + 1
            while (dimEnd < assessmentJson.length && count > 0) {
                when (assessmentJson[dimEnd]) {
                    '{' -> count++
                    '}' -> count--
                }
                dimEnd++
            }
            return extractDimensionAssessment(assessmentJson.substring(dimStart, dimEnd))
        }

        return AnswerAssessment(
            coverage = extractDimension("coverage"),
            depth = extractDimension("depth"),
            temporality = extractDimension("temporality"),
            authority = extractDimension("authority"),
            consistency = extractDimension("consistency")
        )
    }

    private fun createDefaultAssessment(): AnswerAssessment = AnswerAssessment(
        coverage = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment"),
        depth = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment"),
        temporality = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment"),
        authority = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment"),
        consistency = DimensionAssessment(satisfied = false, rationale = "Failed to parse assessment")
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

    private fun buildUserPrompt(
        input: IncrementalSynthesisInput,
        globalImages: List<GlobalImage>
    ): String {
        val imageIdToNumbered = globalImages.withIndex()
            .associate { (index, img) -> img.originalId to (index + 1).toString() }

        val nextCitationNumber = input.currentCitedSourceUrls.size + 1

        return buildString {
            appendLine("# Query")
            appendLine(input.query)
            appendLine()

            if (input.sessionHistory.isNotEmpty()) {
                appendLine("# Prior Session History")
                appendLine(input.sessionHistory.toPromptSummary())
                appendLine()
            }

            if (input.fulfillmentRequirements.isNotEmpty()) {
                appendLine("# Fulfillment Requirements")
                input.fulfillmentRequirements.forEachIndexed { index, req ->
                    appendLine("${index + 1}. $req")
                }
                appendLine()
            }

            appendLine("# Current Answer")
            appendLine(input.currentAnswer)
            appendLine()

            appendLine("# Current Citations")
            input.currentCitedSourceUrls.forEachIndexed { index, url ->
                appendLine("[${index + 1}] $url")
            }
            appendLine("(New citations start from [$nextCitationNumber])")
            appendLine()

            appendLine("# Current Assessment")
            appendLine("Coverage: ${if (input.currentAssessment.coverage.satisfied) "satisfied" else "NOT satisfied"} -- \"${input.currentAssessment.coverage.rationale}\"")
            appendLine("Depth: ${if (input.currentAssessment.depth.satisfied) "satisfied" else "NOT satisfied"} -- \"${input.currentAssessment.depth.rationale}\"")
            appendLine("Temporality: ${if (input.currentAssessment.temporality.satisfied) "satisfied" else "NOT satisfied"} -- \"${input.currentAssessment.temporality.rationale}\"")
            appendLine("Authority: ${if (input.currentAssessment.authority.satisfied) "satisfied" else "NOT satisfied"} -- \"${input.currentAssessment.authority.rationale}\"")
            appendLine("Consistency: ${if (input.currentAssessment.consistency.satisfied) "satisfied" else "NOT satisfied"} -- \"${input.currentAssessment.consistency.rationale}\"")
            appendLine()

            if (input.previouslySearchedQueries.isNotEmpty()) {
                appendLine("# Previously searched queries (DO NOT suggest these again)")
                input.previouslySearchedQueries.forEach { query ->
                    appendLine("- $query")
                }
                appendLine()
            }

            appendLine("# Current Date")
            appendLine(java.time.LocalDate.now().toString())
            appendLine()

            appendLine("# New Sources")
            input.newSources.forEachIndexed { index, source ->
                if (source.relevantFacts.isNotEmpty()) {
                    val sourceNumber = nextCitationNumber + index
                    appendLine("## Source $sourceNumber")
                    appendLine("URL: ${source.url}")
                    appendLine("Intention: ${source.intention}")
                    appendLine("Content Date: ${source.contentDate ?: "Not found"}")
                    appendLine()
                    appendLine("### Facts")
                    source.relevantFacts.forEach { fact ->
                        appendLine("- ${fact.fact}")
                    }

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
        }
    }

    private data class GlobalImage(val originalId: String)

    private fun collectGlobalImages(sources: List<EvaluatedSource>): List<GlobalImage> {
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
