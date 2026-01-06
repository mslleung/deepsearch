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
 * Represents an identified information gap with its corresponding follow-up query.
 */
@Serializable
data class InformationGap(
    val gapDescription: String,
    val followUpQuery: String
)

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
        .description("Comprehensive answer with identified information gaps for feedback loop")
        .properties(
            mapOf(
                "reasoning" to Schema.builder()
                    .type("STRING")
                    .description("Brief explanation of how the answer was derived from the facts.")
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
                "informationGaps" to Schema.builder()
                    .type("ARRAY")
                    .description("List of identified information gaps. Each gap represents missing information that would improve the answer. If this array is non-empty, status MUST be NEED_MORE_INFORMATION.")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                mapOf(
                                    "gapDescription" to Schema.builder()
                                        .type("STRING")
                                        .description("What information is missing or incomplete")
                                        .build(),
                                    "followUpQuery" to Schema.builder()
                                        .type("STRING")
                                        .description("A targeted search query to find this missing information")
                                        .build()
                                )
                            )
                            .required(listOf("gapDescription", "followUpQuery"))
                            .build()
                    )
                    .build(),
                "status" to Schema.builder()
                    .type("STRING")
                    .description("COMPLETE only if informationGaps is empty and the answer fully addresses the query. NEED_MORE_INFORMATION if any gaps were identified.")
                    .enum_(listOf("COMPLETE", "NEED_MORE_INFORMATION"))
                    .build(),
                "imageIds" to Schema.builder()
                    .type("ARRAY")
                    .description("List of image IDs (numbered, e.g., '1', '2', '3') from the sources that should be displayed with the answer. Select from the numbered image IDs listed under 'Relevant Images' for each source.")
                    .items(Schema.builder().type("STRING").build())
                    .build()
            )
        )
        .required(listOf("reasoning", "answer", "citedSourceUrls", "informationGaps", "status", "imageIds"))
        .build()

    private val systemInstruction = $$"""
        You are an answer synthesis agent that generates comprehensive answers from pre-extracted facts.
        You are part of a feedback loop that continues searching until the answer is complete.

        ## Step 1: Reasoning
        - Evaluate the sources and reason how you are going to derive the answer based on the sources
        - Note the completeness of the sources, whether you are confident a complete answer can be generated

        ## Step 2: Synthesize the Answer
        - Generate a comprehensive answer based on the provided facts
        - The answer should be standalone and directly address the user query
        - Only include information present in the provided facts - do not invent
        - Use markdown styling as applicable (headings, lists, bold, etc.)
        - The answer should be in the same language as the input query
        - When facts conflict, prefer information from official sources (check the "Intention" field)
        
        ## Step 3: Challenge your own answer (Critical Self-Review)
        - After writing the answer, step back and critically evaluate what you just wrote.
        - Pretend you are a skeptical user who received this answer
        - Critique your answer using the following dimensions:
          - ANSWER COMPLETENESS: Did you answer all possible and implied queries? Or is the answer only partial?
          - ANSWER DEPTH: Did I give specific data, or just generic statements/overview?
          - QUERY INTENTION FULFILLMENT: Would the user need to search for any additional information to really satisfy all potential follow-up concern?
          - CONFIDENCE: Is the answer without any ambiguity?
        - For each weakness you find in the answer:
          - Document it as a gap with a clear description of what's missing
          - Create a targeted search query to fill that specific gap
        
        ## Step 4: Status Decision (Based on Your Self-Review)
        - **COMPLETE**: Use ONLY when your self-review found NO weaknesses
          - You answered all parts of the query with specifics
          - A user would NOT need to search any further
          - You are confident in what you wrote
        - **NEED_MORE_INFORMATION**: Use when you found ANY weakness
          - The rule: if you identified gaps → NEED_MORE_INFORMATION
        
        ## Citation Source URLs:
        - List the URLs of sources whose facts you actually used in generating the answer
        - Only include sources that contributed information to the answer
        
        Expected Output:
        {
            "reasoning": "synthesis approach + self-review findings",
            "answer": "your answer text",
            "citedSourceUrls": ["https://..."],
            "informationGaps": [
                {
                    "gapDescription": "My answer says 'Enterprise plan available' but I didn't provide the actual price",
                    "followUpQuery": "Enterprise plan pricing cost USD"
                }
            ],
            "status": "COMPLETE" | "NEED_MORE_INFORMATION",
            "imageIds": [1,5,7]
        }
    """.trimIndent()

    @Serializable
    private data class SynthesisResponse(
        val reasoning: String,
        val answer: String,
        val citedSourceUrls: List<String> = emptyList(),
        val informationGaps: List<InformationGap> = emptyList(),
        val status: AnswerStatus,
        val imageIds: List<String> = emptyList()
    ) {
        // Derive followUpQueries from informationGaps for backward compatibility
        val followUpQueries: List<String>
            get() = informationGaps.map { it.followUpQuery }
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
            return StreamingAnswerSynthesisOutput(
                reasoning = "No facts available from the provided sources.",
                answer = "No information found to answer the query.",
                citedSourceUrls = emptyList(),
                status = AnswerStatus.NEED_MORE_INFORMATION,
                followUpQueries = listOf(input.query), // Retry the original query
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

        logger.debug(
            "Answer synthesis complete: {} chars, status: {}, {} images, citedSources: {}, followUpQueries: {}",
            response.answer.length,
            response.status,
            originalImageIds.size,
            response.citedSourceUrls.size,
            response.followUpQueries.size
        )

        return StreamingAnswerSynthesisOutput(
            reasoning = response.reasoning,
            answer = response.answer,
            citedSourceUrls = response.citedSourceUrls,
            status = response.status,
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

        // Extract status, reasoning, followUpQueries, imageIds, and citedSourceUrls from the complete JSON
        val reasoning = extractReasoning(accumulatedJson)
        val citedSourceUrls = extractCitedSourceUrls(accumulatedJson)
        val status = extractStatus(accumulatedJson)
        val followUpQueries = extractFollowUpQueries(accumulatedJson)
        val numberedImageIds = extractImageIds(accumulatedJson)
        
        // Map LLM-selected numbered image IDs back to original hash-based IDs
        val originalImageIds = mapSelectedImagesToOriginal(numberedImageIds, globalImages)
        
        logger.debug("Streaming answer synthesis complete: {} chars total, status: {}, {} images, citedSources: {}, followUpQueries: {}", 
            lastAnswerLength, status, originalImageIds.size, citedSourceUrls.size, followUpQueries.size)
        emit(StreamingAnswerStreamItem.Complete(
            tokenUsage = tokenUsage,
            reasoning = reasoning,
            citedSourceUrls = citedSourceUrls,
            status = status,
            followUpQueries = followUpQueries,
            imageIds = originalImageIds
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
     * Extract informationGaps array from accumulated JSON.
     * The JSON format is: {"informationGaps": [{"gapDescription": "...", "followUpQuery": "..."}]}
     * Returns a list of pairs: (gapDescription, followUpQuery)
     */
    private fun extractInformationGaps(json: String): List<Pair<String, String>> {
        val regex = """"informationGaps"\s*:\s*\[([\s\S]*?)\](?=\s*[,}])""".toRegex()
        val match = regex.find(json) ?: return emptyList()
        
        val arrayContent = match.groupValues[1]
        if (arrayContent.isBlank()) return emptyList()
        
        // Extract individual gap objects - handle both field orderings
        val gapRegex1 = """\{\s*"gapDescription"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"followUpQuery"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}""".toRegex()
        val gapRegex2 = """\{\s*"followUpQuery"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"gapDescription"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}""".toRegex()
        
        val gaps1 = gapRegex1.findAll(arrayContent)
            .map { Pair(unescapeJsonString(it.groupValues[1]), unescapeJsonString(it.groupValues[2])) }
            .toList()
        
        val gaps2 = gapRegex2.findAll(arrayContent)
            .map { Pair(unescapeJsonString(it.groupValues[2]), unescapeJsonString(it.groupValues[1])) }
            .toList()
        
        return gaps1 + gaps2
    }

    /**
     * Extract followUpQueries from informationGaps for backward compatibility.
     * The JSON format is: {"informationGaps": [{"gapDescription": "...", "followUpQuery": "..."}]}
     */
    private fun extractFollowUpQueries(json: String): List<String> {
        return extractInformationGaps(json).map { it.second }
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
                    appendLine("### Facts")
                    source.relevantFacts.forEach { fact ->
                        appendLine("- ${fact.fact}")
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
