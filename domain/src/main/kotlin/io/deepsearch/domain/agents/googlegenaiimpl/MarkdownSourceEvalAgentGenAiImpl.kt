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
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.RelevantFact
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Markdown Source Evaluation agent that evaluates a single markdown source and extracts facts.
 * 
 * For the source, the agent:
 * - Determines the intention (purpose) of the webpage
 * - Assesses relevance to the query
 * - Extracts facts relevant to the query
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
                    .build()
            )
        )
        .required(listOf("fact"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Evaluated source with intention, relevance assessment, extracted facts, and relevant images")
        .properties(
            mapOf(
                "intention" to Schema.builder().type("STRING")
                    .description("Describes the purpose of the webpage (e.g., 'Official pricing page showing subscription tiers', 'Blog post announcing new features from March 2024', 'Third-party review comparing products')")
                    .build(),
                "contentDate" to Schema.builder().type("STRING")
                    .description("Date extracted from content (null/empty if no date found)")
                    .nullable(true)
                    .build(),
                "relevanceAssessment" to Schema.builder().type("STRING")
                    .description("Describes how the page relates to the query and whether it contains useful information (e.g., 'Directly answers the pricing question with current tier information', 'Mentions the topic briefly but focuses on unrelated features', 'Not relevant - page is about a different product')")
                    .build(),
                "relevantFacts" to Schema.builder()
                    .type("ARRAY")
                    .items(relevantFactSchema)
                    .description("List of facts extracted from the source that are relevant to the query. Empty if page is not relevant.")
                    .build(),
                "relevantImageIds" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("List of image IDs (numbered, e.g., '1', '2') from this source that are relevant to answering the query. Only include truly useful images like product screenshots, diagrams, or charts. Most sources should return an empty array.")
                    .build()
            )
        )
        .required(listOf("intention", "relevanceAssessment", "relevantFacts", "relevantImageIds"))
        .build()

    private val systemInstruction = """
        You are a source evaluation and fact extraction agent. Your job is to understand the purpose of a webpage, assess its relevance to a query, and extract relevant facts from markdown content.
        
        Input: 
        - Current date
        - User query (with site: context)
        - Single markdown source (full markdown content with tables, images, and formatting)
        
        For the source, you must output (in this order):
        
        1. **Intention** (intention):
           Describe the purpose of the webpage in a concise sentence. Consider:
           - What type of page is this? (pricing page, documentation, blog post, forum discussion, etc.)
           - Is it official content or third-party content?
           - Is it current/living content or a dated snapshot?
           Examples:
           - "Official pricing page showing current subscription tiers and features"
           - "Company blog post from January 2024 announcing a new feature"
           - "Third-party review comparing multiple products"
           - "Reddit discussion thread about user experiences"
        
        2. **Content Date** (contentDate):
           - Extract the publication or update date if available
           - Look for dates in headers, metadata, or content
           - If no date is found, leave it null/empty
        
        3. **Relevance Assessment** (relevanceAssessment):
           Describe how the page relates to the query:
           - Does it directly answer the query?
           - Does it partially address the query?
           - Is it not relevant to the query?
           Be specific about what information is or isn't present.
           Examples:
           - "Directly answers the pricing question with comprehensive tier breakdown"
           - "Mentions pricing briefly in passing but focuses on feature comparison"
           - "Not relevant - this page discusses a different product entirely"
        
        4. **Extract Relevant Facts** (relevantFacts):
           - Extract all facts that are directly relevant to answering the query
           - Each fact should be a complete, standalone statement
           - Only include facts that help answer the query
           - If the page is not relevant, return an empty array
        
        5. **Select Relevant Images** (relevantImageIds):
           - Sources may contain images in format: `<image id="N">description</image>` or `<image id="N" placeholder>alt text</image>`
           - Identify images that would genuinely help answer the query (e.g., product screenshots, pricing tables, feature diagrams, charts)
           - Return an array of image IDs (the number only, e.g., ["1", "3"]) for images that add value
           - Most sources should return an EMPTY array [] - only include images that are truly necessary
           - Do NOT include decorative images, logos, icons, or generic stock photos
        
        Output Format:
        {
          "intention": "Official pricing page showing current subscription tiers",
          "contentDate": "2024-07-25",
          "relevanceAssessment": "Directly answers the pricing question with detailed tier information",
          "relevantFacts": [
            {"fact": "The Pro plan costs ${'$'}99/month"},
            {"fact": "Enterprise pricing requires contacting sales"}
          ],
          "relevantImageIds": ["1", "2"]
        }
    """.trimIndent()

    @Serializable
    private data class LlmRelevantFact(
        val fact: String
    )

    @Serializable
    private data class EvalResponse(
        val intention: String,
        val contentDate: String? = null,
        val relevanceAssessment: String,
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
            "[{}] response for {}: intention='{}', facts={}, images={}",
            MarkdownSourceEvalAgentGenAiImpl::class.simpleName,
            markdownSource.url,
            response.intention.take(50),
            response.relevantFacts.size,
            response.relevantImageIds.size
        )

        // If no facts extracted, return null evaluated source
        if (response.relevantFacts.isEmpty()) {
            logger.debug("Source {} has no relevant facts, returning null", markdownSource.url)
            return MarkdownSourceEvalOutput(
                evaluatedSource = null,
                tokenUsage = tokenUsage
            )
        }

        // Convert facts
        val relevantFacts = response.relevantFacts.map { llmFact ->
            RelevantFact(fact = llmFact.fact)
        }

        // Map numbered image IDs back to original hash-based IDs (filtering out placeholders)
        val originalImageIds = mapNumberedIdsToOriginal(response.relevantImageIds, imageMapping)

        val evaluatedSource = EvaluatedSource(
            url = markdownSource.url,
            title = markdownSource.title,
            description = markdownSource.description,
            relevantFacts = relevantFacts,
            contentDate = response.contentDate,
            intention = response.intention,
            relevanceAssessment = response.relevanceAssessment,
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
