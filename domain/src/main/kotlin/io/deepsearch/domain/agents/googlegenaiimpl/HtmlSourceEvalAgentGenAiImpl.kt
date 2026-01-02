package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.HtmlSourceEvalInput
import io.deepsearch.domain.agents.HtmlSourceEvalOutput
import io.deepsearch.domain.agents.IHtmlSourceEvalAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.RelevantFact
import io.deepsearch.domain.models.valueobjects.SourceClassification
import io.deepsearch.domain.models.valueobjects.SourceRelevance
import io.deepsearch.domain.models.valueobjects.SourceType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * HTML Source Evaluation agent that evaluates a single HTML preview source and extracts classified facts.
 * 
 * For the source, the agent:
 * - Extracts facts relevant to the query
 * - Marks whether each fact is from a table/grid (isInTable)
 * - Classifies the source type (OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, OTHERS)
 * 
 * Facts where isInTable=true are filtered out before returning, since table
 * data in HTML previews may be inaccurate.
 * 
 * This is a stateless agent that processes one source at a time, enabling parallel processing.
 * Used in the preview path for early exit with conservative fact extraction.
 */
class HtmlSourceEvalAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IHtmlSourceEvalAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val relevantFactSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A fact extracted from the source with metadata")
        .properties(
            mapOf(
                "fact" to Schema.builder()
                    .type("STRING")
                    .description("The extracted fact as a complete, standalone statement")
                    .build(),
                "isInTable" to Schema.builder()
                    .type("BOOLEAN")
                    .description("True if the fact is extracted from a table, grid, or tabular structure")
                    .build(),
                "classification" to Schema.builder()
                    .type("STRING")
                    .description("Source classification: OFFICIAL_LIVING_DOC (main pages like /pricing, /features), OFFICIAL_SNAPSHOT (dated content like /blog, /news), or OTHERS (external reviews, forums, UGC)")
                    .enum_(listOf("OFFICIAL_LIVING_DOC", "OFFICIAL_SNAPSHOT", "OTHERS"))
                    .build()
            )
        )
        .required(listOf("fact", "isInTable", "classification"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Evaluated source with extracted facts")
        .properties(
            mapOf(
                "sourceClassification" to Schema.builder().type("STRING")
                    .description("Source type classification (OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, THIRD_PARTY_REVIEW, FORUM_DISCUSSION)")
                    .build(),
                "contentDate" to Schema.builder().type("STRING")
                    .description("Date extracted from content (null/empty if no date found)")
                    .nullable(true)
                    .build(),
                "relevanceReasoning" to Schema.builder().type("STRING")
                    .description("Brief reasoning for why this source is or is not relevant")
                    .build(),
                "relevance" to Schema.builder().type("STRING")
                    .description("How relevant the source is to the query (CANONICAL, PARTIAL_MENTION, NOT_RELEVANT)")
                    .enum_(listOf("CANONICAL", "PARTIAL_MENTION", "NOT_RELEVANT"))
                    .build(),
                "relevantFacts" to Schema.builder()
                    .type("ARRAY")
                    .items(relevantFactSchema)
                    .description("List of relevant facts extracted from the source (empty if NOT_RELEVANT)")
                    .build()
            )
        )
        .required(listOf("sourceClassification", "relevanceReasoning", "relevance", "relevantFacts"))
        .build()

    private val systemInstruction = """
        You are a source classification and fact extraction agent. Your job is to extract metadata, categorize web content, and extract relevant facts from a single HTML preview source.
        
        Input: 
        - Current date
        - User query (with site: context)
        - Single HTML source (preview content with cleaned HTML structure)
        
        For the source, you must (output fields in this order):
        
        1. **Determine Source Type** (sourceClassification):
           - `OFFICIAL_LIVING_DOC`: A main page (e.g., /pricing, /home, /features) intended to reflect current state.
           - `OFFICIAL_SNAPSHOT`: A dated company update (e.g., /blog, /press, /news).
           - `THIRD_PARTY_REVIEW`: External review or news site.
           - `FORUM_DISCUSSION`: UGC, Reddit, StackOverflow.
        
        2. **Determine Temporal State** (contentDate):
           - Extract the `contentDate` (if available). Look for publication dates, update dates, or temporal indicators.
           - If no date is found, leave it null/empty.
        
        3. **Relevance Reasoning** (relevanceReasoning):
           - Briefly explain why this source is or is not relevant to the query.
           - This reasoning helps determine the relevance classification.
        
        4. **Determine Relevance** (relevance):
           - `CANONICAL`: The page is a canonical/authoritative source for the query (e.g., official pricing page for a pricing query).
           - `PARTIAL_MENTION`: The page contains some relevant information but isn't the primary source.
           - `NOT_RELEVANT`: The page has no relevant information for the query. Return empty facts array.
        
        5. **Extract Relevant Facts** (relevantFacts - if relevance is CANONICAL or PARTIAL_MENTION):
           - Extract facts from the source that are directly relevant to answering the query
           - Each fact should be a complete, standalone statement
           - Only include facts that help answer the query
           - For each fact, include:
             - `isInTable`: true if from table/grid structure, false otherwise (table data may be inaccurate)
             - `classification`: OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, or OTHERS
        
        Output Format:
        {
          "sourceClassification": "OFFICIAL_LIVING_DOC",
          "contentDate": "2024-07-25",
          "relevanceReasoning": "Why this source is relevant or not",
          "relevance": "CANONICAL",
          "relevantFacts": [
            {"fact": "The product costs ${'$'}99/month", "isInTable": true, "classification": "OFFICIAL_LIVING_DOC"},
            {"fact": "The CEO is Jane Doe", "isInTable": false, "classification": "OFFICIAL_LIVING_DOC"}
          ]
        }
    """.trimIndent()

    @Serializable
    private data class LlmRelevantFact(
        val fact: String,
        val isInTable: Boolean,
        val classification: String
    )

    @Serializable
    private data class EvalResponse(
        val sourceClassification: String,
        val contentDate: String? = null,
        val relevanceReasoning: String,
        val relevance: String,
        val relevantFacts: List<LlmRelevantFact> = emptyList()
    )

    override suspend fun generate(input: HtmlSourceEvalInput): HtmlSourceEvalOutput {
        val searchQuery = input.searchQuery
        val htmlSource = input.htmlSource
        val queryWithSite = "${searchQuery.query} site:${extractDomain(searchQuery.url)}"
        
        logger.debug(
            "Evaluating HTML source for query: '{}', url: {}",
            queryWithSite,
            htmlSource.url
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val userPrompt = buildUserPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<EvalResponse>(this@HtmlSourceEvalAgentGenAiImpl::class.simpleName!!) {
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

        // Parse relevance first to check if source is relevant
        val relevance = try {
            SourceRelevance.valueOf(response.relevance)
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid relevance '{}', defaulting to NOT_RELEVANT", response.relevance)
            SourceRelevance.NOT_RELEVANT
        }

        logger.debug(
            "[{}] response for {}: relevance={}, facts={}",
            HtmlSourceEvalAgentGenAiImpl::class.simpleName,
            htmlSource.url,
            relevance,
            response.relevantFacts.size
        )

        // If not relevant, return null evaluated source
        if (relevance == SourceRelevance.NOT_RELEVANT) {
            logger.debug("Source {} is not relevant, returning null", htmlSource.url)
            return HtmlSourceEvalOutput(
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

        // Filter out facts where isInTable=true (tables in preview HTML are inaccurate)
        val filteredFacts = response.relevantFacts
            .filter { !it.isInTable }
            .map { llmFact ->
                RelevantFact(
                    fact = llmFact.fact,
                    sourceClassification = parseClassification(llmFact.classification)
                )
            }

        // If no facts remain after filtering, return null
        if (filteredFacts.isEmpty()) {
            logger.debug("Source {} has no facts after filtering out table data, returning null", htmlSource.url)
            return HtmlSourceEvalOutput(
                evaluatedSource = null,
                tokenUsage = tokenUsage
            )
        }

        val evaluatedSource = EvaluatedSource(
            url = htmlSource.url,
            title = htmlSource.title,
            description = htmlSource.description,
            relevantFacts = filteredFacts,
            sourceClassification = sourceType,
            contentDate = response.contentDate,
            relevance = relevance,
            relevanceReasoning = response.relevanceReasoning,
            relevantImageIds = emptyList() // Preview path doesn't handle images
        )

        logger.debug(
            "HTML source evaluation complete for {}: {} facts (after filtering table data)",
            htmlSource.url,
            filteredFacts.size
        )

        return HtmlSourceEvalOutput(
            evaluatedSource = evaluatedSource,
            tokenUsage = tokenUsage
        )
    }

    private fun parseClassification(value: String): SourceClassification {
        return when (value.uppercase()) {
            "OFFICIAL_LIVING_DOC" -> SourceClassification.OFFICIAL_LIVING_DOC
            "OFFICIAL_SNAPSHOT" -> SourceClassification.OFFICIAL_SNAPSHOT
            else -> SourceClassification.OTHERS
        }
    }
    
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

    private fun buildUserPrompt(input: HtmlSourceEvalInput): String {
        val queryWithSite = "${input.searchQuery.query} site:${extractDomain(input.searchQuery.url)}"
        val source = input.htmlSource
        return buildString {
            // Include current date for temporal context
            appendLine("# Current Date")
            appendLine(java.time.LocalDate.now().toString())
            appendLine()

            appendLine("# Query")
            appendLine(queryWithSite)
            appendLine()

            appendLine("# HTML Source to Evaluate")
            appendLine("URL: ${source.url}")
            if (source.title != null) {
                appendLine("Title: ${source.title}")
            }
            if (source.description != null) {
                appendLine("Description: ${source.description}")
            }
            appendLine()
            appendLine("## HTML Content")
            appendLine("```html")
            appendLine(source.cleanedHtml)
            appendLine("```")
        }
    }
}

