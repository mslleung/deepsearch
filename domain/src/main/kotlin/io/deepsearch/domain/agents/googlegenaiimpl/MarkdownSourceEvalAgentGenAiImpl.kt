package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IMarkdownSourceEvalAgent
import io.deepsearch.domain.agents.MarkdownSourceEvalInput
import io.deepsearch.domain.agents.MarkdownSourceEvalOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.AnswerType
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.RelevantFact
import io.deepsearch.domain.models.valueobjects.SourceClassification
import io.deepsearch.domain.models.valueobjects.SourceType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Markdown Source Evaluation agent that evaluates a single markdown source and extracts classified facts.
 * 
 * For the source, the agent:
 * - Extracts facts relevant to the query
 * - Classifies the source type (OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, OTHERS)
 * - Determines answer type and temporal metadata
 * - Handles image selection (relevantImageIds)
 * 
 * Unlike the HTML preview agent, this does NOT filter table facts since markdown
 * tables are properly processed and accurate.
 * 
 * This is a stateless agent that processes one source at a time, enabling parallel processing.
 * The isGoodEnough decision is made by the StreamingAnswerSynthesisAgent instead.
 */
class MarkdownSourceEvalAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IMarkdownSourceEvalAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val relevantFactSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A fact extracted from the source")
        .properties(
            mapOf(
                "fact" to Schema.builder()
                    .type("STRING")
                    .description("The extracted fact as a complete, standalone statement")
                    .build(),
                "classification" to Schema.builder()
                    .type("STRING")
                    .description("Source classification: OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, or OTHERS")
                    .enum_(listOf("OFFICIAL_LIVING_DOC", "OFFICIAL_SNAPSHOT", "OTHERS"))
                    .build()
            )
        )
        .required(listOf("fact", "classification"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Evaluated source with extracted facts and relevant images")
        .properties(
            mapOf(
                "isRelevant" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether this source has any relevant facts for the query")
                    .build(),
                "sourceClassification" to Schema.builder().type("STRING")
                    .description("Source type classification (OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, THIRD_PARTY_REVIEW, FORUM_DISCUSSION)")
                    .build(),
                "contentDate" to Schema.builder().type("STRING")
                    .description("Date extracted from content (null/empty if no date found)")
                    .nullable(true)
                    .build(),
                "answerType" to Schema.builder().type("STRING")
                    .description("How directly the source answers the query (DIRECT_ANSWER, INFERRED_ANSWER, PARTIAL_MENTION)")
                    .build(),
                "relevanceJustification" to Schema.builder().type("STRING")
                    .description("Brief reason for why this source is or is not relevant")
                    .build(),
                "relevantFacts" to Schema.builder()
                    .type("ARRAY")
                    .items(relevantFactSchema)
                    .description("List of facts extracted from the source that are relevant to answering the query")
                    .build(),
                "relevantImageIds" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("List of image IDs (numbered, e.g., '1', '2') from this source that are relevant to answering the query. Only include truly useful images like product screenshots, diagrams, or charts. Most sources should return an empty array.")
                    .build()
            )
        )
        .required(listOf("isRelevant", "sourceClassification", "answerType", "relevanceJustification", "relevantFacts", "relevantImageIds"))
        .build()

    private val systemInstruction = """
        You are a source classification and fact extraction agent. Your job is to extract metadata, categorize web content, and extract relevant facts from a single markdown source.
        
        Input: 
        - Current date
        - User query (with site: context)
        - Single markdown source (full markdown content with tables, images, and formatting)
        
        For the source, you must:
        
        1. **Determine if Relevant**:
           - Set isRelevant=true if the source contains facts that help answer the query
           - Set isRelevant=false if the source has no relevant information
        
        2. **Extract Relevant Facts**:
           - Extract all facts from the source that are directly relevant to answering the query
           - Each fact should be a complete, standalone statement
           - Only include facts that help answer the query
           - If a source has no relevant facts, set isRelevant=false and return empty facts array
        
        3. **Classify Each Fact**:
           - OFFICIAL_LIVING_DOC: Fact from a main page (e.g., /pricing, /home, /features) intended to reflect current state
           - OFFICIAL_SNAPSHOT: Fact from dated company content (e.g., /blog, /press, /news)
           - OTHERS: Fact from external reviews, forums, UGC, etc.
        
        4. **Determine Source Type** (Choose one):
           - `OFFICIAL_LIVING_DOC`: A main page (e.g., /pricing, /home, /features) intended to reflect current state.
           - `OFFICIAL_SNAPSHOT`: A dated company update (e.g., /blog, /press, /news).
           - `THIRD_PARTY_REVIEW`: External review or news site.
           - `FORUM_DISCUSSION`: UGC, Reddit, StackOverflow.
        
        5. **Determine Temporal State**:
           - Extract the `contentDate` (if available). Look for publication dates, update dates, or temporal indicators.
           - If no date is found, leave it null/empty.
        
        6. **Determine Answer Type**:
           - `DIRECT_ANSWER`: The page explicitly lists the answer (e.g., a pricing table).
           - `INFERRED_ANSWER`: The answer must be guessed or calculated.
           - `PARTIAL_MENTION`: Mentions keywords but doesn't answer the core intent.
        
        7. **Select Relevant Images** (relevantImageIds):
           - Sources may contain images in format: `<image id="N">description</image>` or `<image id="N" placeholder>alt text</image>`
           - Identify images that would genuinely help answer the query (e.g., product screenshots, pricing tables, feature diagrams, charts)
           - Return an array of image IDs (the number only, e.g., ["1", "3"]) for images that add value
           - Most sources should return an EMPTY array [] - only include images that are truly necessary
           - Do NOT include decorative images, logos, icons, or generic stock photos
        
        Output Format:
        {
          "isRelevant": true,
          "sourceClassification": "OFFICIAL_LIVING_DOC",
          "contentDate": "2024-07-25",
          "answerType": "DIRECT_ANSWER",
          "relevanceJustification": "Why this source is relevant or not",
          "relevantFacts": [
            {"fact": "The product costs ${'$'}99/month", "classification": "OFFICIAL_LIVING_DOC"}
          ],
          "relevantImageIds": ["1", "2"]
        }
    """.trimIndent()

    @Serializable
    private data class LlmRelevantFact(
        val fact: String,
        val classification: String
    )

    @Serializable
    private data class EvalResponse(
        val isRelevant: Boolean,
        val sourceClassification: String,
        val contentDate: String? = null,
        val answerType: String,
        val relevanceJustification: String,
        val relevantFacts: List<LlmRelevantFact> = emptyList(),
        val relevantImageIds: List<String> = emptyList()
    )

    override suspend fun generate(input: MarkdownSourceEvalInput): MarkdownSourceEvalOutput {
        val searchQuery = input.searchQuery
        val markdownSource = input.markdownSource
        val queryWithSite = "${searchQuery.query} site:${extractDomain(searchQuery.url)}"
        
        logger.debug(
            "Evaluating markdown source for query: '{}', url: {}",
            queryWithSite,
            markdownSource.url
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        // Transform markdown to use numbered image IDs for LLM
        val (transformedMarkdown, imageMapping) = transformImageIdsForLlm(markdownSource.markdown)
        val userPrompt = buildUserPrompt(input, transformedMarkdown)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<EvalResponse>(this@MarkdownSourceEvalAgentGenAiImpl::class.simpleName!!) {
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
            "[{}] response for {}: isRelevant={}, facts={}, images={}",
            MarkdownSourceEvalAgentGenAiImpl::class.simpleName,
            markdownSource.url,
            response.isRelevant,
            response.relevantFacts.size,
            response.relevantImageIds.size
        )

        // If not relevant, return null evaluated source
        if (!response.isRelevant || response.relevantFacts.isEmpty()) {
            logger.debug("Source {} is not relevant, returning null", markdownSource.url)
            return MarkdownSourceEvalOutput(
                evaluatedSource = null,
                tokenUsage = tokenUsage
            )
        }

        // Parse source type
        val sourceType = try {
            SourceType.valueOf(response.sourceClassification)
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid source classification '{}', defaulting to OFFICIAL_LIVING_DOC", response.sourceClassification)
            SourceType.OFFICIAL_LIVING_DOC
        }
        
        // Parse answer type
        val answerType = try {
            AnswerType.valueOf(response.answerType)
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid answer type '{}', defaulting to PARTIAL_MENTION", response.answerType)
            AnswerType.PARTIAL_MENTION
        }

        // Convert facts
        val relevantFacts = response.relevantFacts.map { llmFact ->
            RelevantFact(
                fact = llmFact.fact,
                sourceClassification = parseSourceClassification(llmFact.classification)
            )
        }

        // Map numbered image IDs back to original hash-based IDs (filtering out placeholders)
        val originalImageIds = mapNumberedIdsToOriginal(response.relevantImageIds, imageMapping)

        val evaluatedSource = EvaluatedSource(
            url = markdownSource.url,
            title = markdownSource.title,
            description = markdownSource.description,
            relevantFacts = relevantFacts,
            sourceClassification = sourceType,
            contentDate = response.contentDate,
            answerType = answerType,
            relevanceJustification = response.relevanceJustification,
            relevantImageIds = originalImageIds
        )

        logger.debug(
            "Markdown source evaluation complete for {}: {} facts, {} images",
            markdownSource.url,
            relevantFacts.size,
            originalImageIds.size
        )

        return MarkdownSourceEvalOutput(
            evaluatedSource = evaluatedSource,
            tokenUsage = tokenUsage
        )
    }

    private fun parseSourceClassification(value: String): SourceClassification {
        return when (value.uppercase()) {
            "OFFICIAL_LIVING_DOC" -> SourceClassification.OFFICIAL_LIVING_DOC
            "OFFICIAL_SNAPSHOT" -> SourceClassification.OFFICIAL_SNAPSHOT
            else -> SourceClassification.OTHERS
        }
    }

    private fun buildUserPrompt(input: MarkdownSourceEvalInput, transformedMarkdown: String): String {
        val queryWithSite = "${input.searchQuery.query} site:${extractDomain(input.searchQuery.url)}"
        val source = input.markdownSource
        return buildString {
            // Include current date for temporal context
            appendLine("# Current Date")
            appendLine(java.time.LocalDate.now().toString())
            appendLine()
            
            appendLine("# Query")
            appendLine(queryWithSite)
            appendLine()

            appendLine("# Markdown Source to Evaluate")
            appendLine("URL: ${source.url}")
            if (source.title != null) {
                appendLine("Title: ${source.title}")
            }
            if (source.description != null) {
                appendLine("Description: ${source.description}")
            }
            appendLine()
            appendLine("## Markdown Content")
            appendLine(transformedMarkdown)
        }
    }

    companion object {
        /** Marker used to identify placeholder images in the mapping */
        const val PLACEHOLDER_MARKER = "__PLACEHOLDER__"
        
        /**
         * Extracts the domain from a URL string.
         */
        private fun extractDomain(url: String): String {
            return try {
                java.net.URI(url).host?.lowercase() ?: url
            } catch (e: Exception) {
                url
            }
        }

        // Regex patterns for image tag matching
        private val FULL_IMAGE_REGEX = """<image\s+id="(img-[^"]+)"(?:\s*>([^<]*)</image>|[^>]*/>)""".toRegex()
        private val PLACEHOLDER_IMAGE_REGEX = """<image\s+placeholder(?:\s+alt="([^"]*)")?(?:\s*/)?>""".toRegex()

        /**
         * Transforms image IDs in markdown to simple numbered IDs for LLM comprehension.
         *
         * - Converts `<image id="img-xxx">description</image>` to `<image id="1">description</image>`
         * - Converts `<image placeholder alt="..."/>` to `<image id="1" placeholder>alt text</image>`
         *
         * @return Pair of (transformed markdown, mapping from numbered ID to original ID or PLACEHOLDER_MARKER)
         */
        fun transformImageIdsForLlm(markdown: String): Pair<String, Map<String, String>> {
            val imageMapping = mutableMapOf<String, String>()
            var imageCounter = 0

            // First pass: transform full images with hash IDs
            var transformedMarkdown = FULL_IMAGE_REGEX.replace(markdown) { matchResult ->
                imageCounter++
                val numberedId = imageCounter.toString()
                val originalId = matchResult.groupValues[1]
                val description = matchResult.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }

                imageMapping[numberedId] = originalId

                if (description != null) {
                    """<image id="$numberedId">$description</image>"""
                } else {
                    """<image id="$numberedId"/>"""
                }
            }

            // Second pass: transform placeholder images
            transformedMarkdown = PLACEHOLDER_IMAGE_REGEX.replace(transformedMarkdown) { matchResult ->
                imageCounter++
                val numberedId = imageCounter.toString()
                val altText = matchResult.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }

                imageMapping[numberedId] = PLACEHOLDER_MARKER

                if (altText != null) {
                    """<image id="$numberedId" placeholder>$altText</image>"""
                } else {
                    """<image id="$numberedId" placeholder/>"""
                }
            }

            return Pair(transformedMarkdown, imageMapping)
        }

        /**
         * Maps numbered image IDs back to their original hash-based IDs.
         * Filters out placeholder images.
         *
         * @param numberedIds List of numbered IDs selected by the LLM (e.g., ["1", "3"])
         * @param imageMapping Mapping from numbered ID to original ID or PLACEHOLDER_MARKER
         * @return List of original hash-based image IDs (placeholders are excluded)
         */
        fun mapNumberedIdsToOriginal(numberedIds: List<String>, imageMapping: Map<String, String>): List<String> {
            return numberedIds.mapNotNull { numberedId ->
                val originalId = imageMapping[numberedId]
                when {
                    originalId == null -> null
                    originalId == PLACEHOLDER_MARKER -> null  // Exclude placeholders
                    else -> originalId
                }
            }
        }
    }
}

