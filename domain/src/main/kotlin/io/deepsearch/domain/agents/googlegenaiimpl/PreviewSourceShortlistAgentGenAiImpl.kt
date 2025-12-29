package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IPreviewSourceShortlistAgent
import io.deepsearch.domain.agents.PreviewSourceShortlistInput
import io.deepsearch.domain.agents.PreviewSourceShortlistOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.models.valueobjects.RelevantFact
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.models.valueobjects.SourceClassification
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Preview Source Shortlist agent that evaluates HTML sources and extracts classified facts.
 * 
 * For each source, the agent:
 * - Extracts facts relevant to the query
 * - Marks whether each fact is from a table/grid (isInTable)
 * - Classifies the source type (OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, OTHERS)
 * 
 * Facts where isInTable=true are filtered out before returning, since table
 * data in HTML previews may be inaccurate.
 * 
 * Unlike the full markdown shortlist agent, this does NOT determine isGoodEnough.
 * The answer synthesis agent determines flow completion via answerFound.
 */
class PreviewSourceShortlistAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IPreviewSourceShortlistAgent {

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

    private val shortlistedSourceSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A source with its classified relevant facts")
        .properties(
            mapOf(
                "url" to Schema.builder()
                    .type("STRING")
                    .description("The URL of the source")
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
                    .description("Brief reason for inclusion in shortlist")
                    .build(),
                "relevantFacts" to Schema.builder()
                    .type("ARRAY")
                    .items(relevantFactSchema)
                    .description("List of relevant facts extracted from the source")
                    .build()
            )
        )
        .required(listOf("url", "sourceClassification", "answerType", "relevanceJustification", "relevantFacts"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Shortlisted sources with extracted facts")
        .properties(
            mapOf(
                "shortlistedSources" to Schema.builder()
                    .type("ARRAY")
                    .items(shortlistedSourceSchema)
                    .description("List of sources with their classified facts")
                    .build()
            )
        )
        .required(listOf("shortlistedSources"))
        .build()

    private val systemInstruction = """
        You are a source classification and fact extraction agent. Your job is to extract metadata, categorize web content, and extract relevant facts from HTML preview sources.
        
        Input: 
        - Current date
        - User query
        - HTML sources (preview content with cleaned HTML structure)
        
        For each source, you must:
        
        1. **Extract Relevant Facts**:
           - Extract facts from the source that are directly relevant to answering the query
           - Each fact should be a complete, standalone statement
           - Only include facts that help answer the query
           - If a source has no relevant facts, do not include it in the shortlist
        
        2. **Mark Table Origin (isInTable)**:
           - Mark isInTable=true if the fact comes from:
             - <table>, <tr>, <td>, <th> elements
             - Pricing grids or comparison matrices
             - Repeated <div>-soup patterns that form rows/columns
             - Any tabular data structure
           - Mark isInTable=false for facts from prose paragraphs, headings, or regular text
           - IMPORTANT: Table data in HTML previews may be inaccurate, so this flag is used to filter
        
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
        
        Shortlist Management:
        - Only include sources that have relevant facts for the query
        - Prioritize OFFICIAL_LIVING_DOC with DIRECT_ANSWER for factual queries
        - Each source should add unique value through its extracted facts
        
        Output Format:
        {
          "shortlistedSources": [
            {
              "url": "String",
              "sourceClassification": "OFFICIAL_LIVING_DOC",
              "contentDate": "2024-07-25",
              "answerType": "DIRECT_ANSWER",
              "relevanceJustification": "Why this source is included",
              "relevantFacts": [
                {"fact": "The product costs $99/month", "isInTable": true, "classification": "OFFICIAL_LIVING_DOC"},
                {"fact": "The CEO is Jane Doe", "isInTable": false, "classification": "OFFICIAL_LIVING_DOC"}
              ]
            }
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
    private data class LlmShortlistedSource(
        val url: String,
        val sourceClassification: String,
        val contentDate: String? = null,
        val answerType: String,
        val relevanceJustification: String,
        val relevantFacts: List<LlmRelevantFact>
    )

    @Serializable
    private data class ShortlistResponse(
        val shortlistedSources: List<LlmShortlistedSource>
    )

    override suspend fun generate(input: PreviewSourceShortlistInput): PreviewSourceShortlistOutput {
        logger.debug(
            "Classifying preview sources for query: '{}', sources: {}",
            input.query,
            input.htmlSources.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.htmlSources.isEmpty()) {
            logger.debug("Empty HTML sources, returning empty result")
            return PreviewSourceShortlistOutput(
                shortlistedSources = emptyList(),
                tokenUsage = tokenUsage
            )
        }

        val userPrompt = buildUserPrompt(input)

        val response = retryLlmCall<ShortlistResponse>(this::class.simpleName!!) {
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

        logger.debug(
            "[{}] response: {}",
            PreviewSourceShortlistAgentGenAiImpl::class.simpleName,
            response
        )

        // Convert LLM response to domain model and filter out isInTable=true facts
        val shortlistedSources = response.shortlistedSources.mapNotNull { llmSource ->
            // Parse source type
            val sourceType = try {
                io.deepsearch.domain.models.valueobjects.SourceType.valueOf(llmSource.sourceClassification)
            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid source classification '{}', defaulting to OFFICIAL_LIVING_DOC", llmSource.sourceClassification)
                io.deepsearch.domain.models.valueobjects.SourceType.OFFICIAL_LIVING_DOC
            }
            
            // Parse answer type
            val answerType = try {
                io.deepsearch.domain.models.valueobjects.AnswerType.valueOf(llmSource.answerType)
            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid answer type '{}', defaulting to PARTIAL_MENTION", llmSource.answerType)
                io.deepsearch.domain.models.valueobjects.AnswerType.PARTIAL_MENTION
            }

            // Filter out facts where isInTable=true (tables in preview HTML are inaccurate)
            val filteredFacts = llmSource.relevantFacts
                .filter { !it.isInTable }
                .map { llmFact ->
                    RelevantFact(
                        fact = llmFact.fact,
                        sourceClassification = parseClassification(llmFact.classification)
                    )
                }

            // Only include sources that have at least one fact after filtering
            if (filteredFacts.isEmpty()) {
                logger.debug("Source {} has no facts after filtering out table data, excluding from shortlist", llmSource.url)
                null
            } else {
                ShortlistedSource(
                    url = llmSource.url,
                    relevantFacts = filteredFacts,
                    sourceClassification = sourceType,
                    contentDate = llmSource.contentDate,
                    answerType = answerType,
                    relevanceJustification = llmSource.relevanceJustification,
                    relevantImageIds = emptyList() // Preview path doesn't handle images
                )
            }
        }

        logger.debug(
            "Preview source shortlist complete: {} sources, {} total facts (after filtering table data)",
            shortlistedSources.size,
            shortlistedSources.sumOf { it.relevantFacts.size }
        )

        return PreviewSourceShortlistOutput(
            shortlistedSources = shortlistedSources,
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

    private fun buildUserPrompt(input: PreviewSourceShortlistInput): String {
        return buildString {
            // Include current date for temporal context
            appendLine("# Current Date")
            appendLine(java.time.LocalDate.now().toString())
            appendLine()

            appendLine("# Query")
            appendLine(input.query)
            appendLine()

            appendLine("# HTML Sources to Evaluate")
            input.htmlSources.forEachIndexed { index, source ->
                appendLine("## Source ${index + 1}")
                appendLine("URL: ${source.url}")
                if (source.title != null) {
                    appendLine("Title: ${source.title}")
                }
                if (source.description != null) {
                    appendLine("Description: ${source.description}")
                }
                appendLine()
                appendLine("### HTML Content")
                appendLine("```html")
                appendLine(source.cleanedHtml)
                appendLine("```")
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }
}

