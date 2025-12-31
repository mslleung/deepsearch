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
                    .description("List of relevant facts extracted from the source")
                    .build()
            )
        )
        .required(listOf("isRelevant", "sourceClassification", "answerType", "relevanceJustification", "relevantFacts"))
        .build()

    private val systemInstruction = """
        You are a source classification and fact extraction agent. Your job is to extract metadata, categorize web content, and extract relevant facts from a single HTML preview source.
        
        Input: 
        - Current date
        - User query (with site: context)
        - Single HTML source (preview content with cleaned HTML structure)
        
        For the source, you must:
        
        1. **Determine if Relevant**:
           - Set isRelevant=true if the source contains facts that help answer the query
           - Set isRelevant=false if the source has no relevant information
        
        2. **Extract Relevant Facts**:
           - Extract facts from the source that are directly relevant to answering the query
           - Each fact should be a complete, standalone statement
           - Only include facts that help answer the query
           - If a source has no relevant facts, set isRelevant=false and return empty facts array
        
        3. **Mark Table Origin (isInTable)**:
           - Mark isInTable=true if the fact comes from:
             - <table>, <tr>, <td>, <th> elements
             - Pricing grids or comparison matrices
             - Repeated <div>-soup patterns that form rows/columns
             - Any tabular data structure
           - Mark isInTable=false for facts from prose paragraphs, headings, or regular text
           - IMPORTANT: Table data in HTML previews may be inaccurate, so this flag is used to filter
        
        4. **Classify Each Fact**:
           - OFFICIAL_LIVING_DOC: Fact from a main page (e.g., /pricing, /home, /features) intended to reflect current state
           - OFFICIAL_SNAPSHOT: Fact from dated company content (e.g., /blog, /press, /news)
           - OTHERS: Fact from external reviews, forums, UGC, etc.
        
        5. **Determine Source Type** (Choose one):
           - `OFFICIAL_LIVING_DOC`: A main page (e.g., /pricing, /home, /features) intended to reflect current state.
           - `OFFICIAL_SNAPSHOT`: A dated company update (e.g., /blog, /press, /news).
           - `THIRD_PARTY_REVIEW`: External review or news site.
           - `FORUM_DISCUSSION`: UGC, Reddit, StackOverflow.
        
        6. **Determine Temporal State**:
           - Extract the `contentDate` (if available). Look for publication dates, update dates, or temporal indicators.
           - If no date is found, leave it null/empty.
        
        7. **Determine Answer Type**:
           - `DIRECT_ANSWER`: The page explicitly lists the answer (e.g., a pricing table).
           - `INFERRED_ANSWER`: The answer must be guessed or calculated.
           - `PARTIAL_MENTION`: Mentions keywords but doesn't answer the core intent.
        
        Output Format:
        {
          "isRelevant": true,
          "sourceClassification": "OFFICIAL_LIVING_DOC",
          "contentDate": "2024-07-25",
          "answerType": "DIRECT_ANSWER",
          "relevanceJustification": "Why this source is relevant or not",
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
        val isRelevant: Boolean,
        val sourceClassification: String,
        val contentDate: String? = null,
        val answerType: String,
        val relevanceJustification: String,
        val relevantFacts: List<LlmRelevantFact>
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

        logger.debug(
            "[{}] response for {}: isRelevant={}, facts={}",
            HtmlSourceEvalAgentGenAiImpl::class.simpleName,
            htmlSource.url,
            response.isRelevant,
            response.relevantFacts.size
        )

        // If not relevant, return null evaluated source
        if (!response.isRelevant) {
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
        
        // Parse answer type
        val answerType = try {
            AnswerType.valueOf(response.answerType)
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid answer type '{}', defaulting to PARTIAL_MENTION", response.answerType)
            AnswerType.PARTIAL_MENTION
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
            answerType = answerType,
            relevanceJustification = response.relevanceJustification,
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

