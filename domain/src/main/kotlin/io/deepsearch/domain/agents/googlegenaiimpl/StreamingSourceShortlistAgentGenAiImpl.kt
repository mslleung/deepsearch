package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IStreamingSourceShortlistAgent
import io.deepsearch.domain.agents.StreamingSourceShortlistInput
import io.deepsearch.domain.agents.StreamingSourceShortlistOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Streaming Source Shortlist agent that curates high-quality sources for answering a query.
 * Evaluates sources based on content relevance, temporal relevance, and authority.
 * Handles information conflicts by keeping the most relevant sources.
 */
class StreamingSourceShortlistAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IStreamingSourceShortlistAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val shortlistedSourceSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A shortlisted source with classification metadata and reasoning")
        .properties(
            mapOf(
                "url" to Schema.builder().type("STRING")
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
                    .build()
            )
        )
        .required(listOf("url", "sourceClassification", "answerType", "relevanceJustification"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Updated shortlist of sources and sufficiency decision")
        .properties(
            mapOf(
                "shortlist" to Schema.builder()
                    .type("ARRAY")
                    .items(shortlistedSourceSchema)
                    .description("Updated list of shortlisted sources (may add, remove, or modify sources)")
                    .build(),
                "isGoodEnough" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether the current shortlist is sufficient to answer the query comprehensively")
                    .build(),
                "reason" to Schema.builder()
                    .type("STRING")
                    .description("Brief explanation of the sufficiency decision")
                    .build()
            )
        )
        .required(listOf("shortlist", "isGoodEnough", "reason"))
        .build()

    private val systemInstruction = """
        You are a source classification agent. Your job is to extract metadata and categorize web content objectively and decide if the shortlist contains the critical information to answer the user query.
        
        For each source, you must:
        
        1. **Determine Source Type** (Choose one):
           - `OFFICIAL_LIVING_DOC`: A main page (e.g., /pricing, /home, /features) intended to reflect current state.
           - `OFFICIAL_SNAPSHOT`: A dated company update (e.g., /blog, /press, /news).
           - `THIRD_PARTY_REVIEW`: External review or news site.
           - `FORUM_DISCUSSION`: UGC, Reddit, StackOverflow.
        
        2. **Determine Temporal State**:
           - Extract the `contentDate` (if available). Look for publication dates, update dates, or temporal indicators.
           - If no date is found, leave it null/empty.
        
        3. **Determine Answer Type**:
           - `DIRECT_ANSWER`: The page explicitly lists the answer (e.g., a pricing table).
           - `INFERRED_ANSWER`: The answer must be guessed or calculated.
           - `PARTIAL_MENTION`: Mentions keywords but doesn't answer the core intent.
        
        4. **Determine shortlist sufficiency** (isGoodEnough decision):
           - Set isGoodEnough=false if the best source is an `OFFICIAL_SNAPSHOT` (Blog/News) when the query asks for static facts (Price, Specs, Current Features), unless you have verified no Living Doc exists.
           - Set isGoodEnough=false if the answer relies on data that is too old.
           - **ALWAYS** set `isGoodEnough=false` if the source suggests a "New Update" but the URL indicates a blog post (e.g., /blog/pricing-update), as we must find the actual implementation page.
           - Set isGoodEnough=true only when you have high-quality, current sources that directly answer the query.
        
        Shortlist Management:
        - Maximum ~10 sources in shortlist (remove less relevant ones if needed)
        - Each source must add unique value
        - Prioritize OFFICIAL_LIVING_DOC with DIRECT_ANSWER for factual queries
        - If new sources conflict with existing ones, keep the most authoritative and current
        
        Output Format:
        {
          "shortlist": [
            {
              "url": "String",
              "sourceClassification": "OFFICIAL_SNAPSHOT",
              "contentDate": "2024-07-25",
              "answerType": "DIRECT_ANSWER",
              "relevanceJustification": "Why this source is included"
            }
          ],
          "isGoodEnough": true/false,
          "reason": "Brief explanation of isGoodEnough decision"
        }
    """.trimIndent()

    @Serializable
    private data class LlmShortlistedSource(
        val url: String,
        val sourceClassification: String,
        val contentDate: String?,
        val answerType: String,
        val relevanceJustification: String
    )

    @Serializable
    private data class ShortlistResponse(
        val shortlist: List<LlmShortlistedSource>,
        val isGoodEnough: Boolean,
        val reason: String
    )

    override suspend fun generate(input: StreamingSourceShortlistInput): StreamingSourceShortlistOutput {
        logger.debug(
            "Generating source shortlist for query: '{}', current shortlist size: {}, new batch size: {}",
            input.query,
            input.currentShortlist.size,
            input.newMarkdownBatch.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.newMarkdownBatch.isEmpty()) {
            logger.debug("Empty markdown batch, returning current shortlist")
            return StreamingSourceShortlistOutput(
                updatedShortlist = input.currentShortlist,
                isGoodEnough = false,
                reason = "No new sources to evaluate",
                tokenUsage = tokenUsage
            )
        }

        // Build user prompt with query, current shortlist, and new sources
        val userPrompt = buildUserPrompt(input)

        val response = retryLlmCall<ShortlistResponse> {
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

        // Convert LLM response to domain model
        // Need to find the markdown for each URL from the input
        val urlToMarkdown = buildUrlToMarkdownMap(input)

        val updatedShortlist = response.shortlist.mapNotNull { llmSource ->
            val markdown = urlToMarkdown[llmSource.url]
            if (markdown == null) {
                logger.warn("Could not find markdown for URL: {}", llmSource.url)
                null
            } else {
                // Parse enum values from string
                val sourceType = try {
                    io.deepsearch.domain.models.valueobjects.SourceType.valueOf(llmSource.sourceClassification)
                } catch (e: IllegalArgumentException) {
                    logger.warn("Invalid source classification '{}', defaulting to OFFICIAL_LIVING_DOC", llmSource.sourceClassification)
                    io.deepsearch.domain.models.valueobjects.SourceType.OFFICIAL_LIVING_DOC
                }
                
                val answerType = try {
                    io.deepsearch.domain.models.valueobjects.AnswerType.valueOf(llmSource.answerType)
                } catch (e: IllegalArgumentException) {
                    logger.warn("Invalid answer type '{}', defaulting to PARTIAL_MENTION", llmSource.answerType)
                    io.deepsearch.domain.models.valueobjects.AnswerType.PARTIAL_MENTION
                }
                
                ShortlistedSource(
                    url = llmSource.url,
                    markdown = markdown,
                    sourceClassification = sourceType,
                    contentDate = llmSource.contentDate,
                    answerType = answerType,
                    relevanceJustification = llmSource.relevanceJustification
                )
            }
        }

        logger.debug(
            "Shortlist updated: {} sources, isGoodEnough: {}, reason: {}",
            updatedShortlist.size,
            response.isGoodEnough,
            response.reason
        )

        return StreamingSourceShortlistOutput(
            updatedShortlist = updatedShortlist,
            isGoodEnough = response.isGoodEnough,
            reason = response.reason,
            tokenUsage = tokenUsage
        )
    }

    private fun buildUserPrompt(input: StreamingSourceShortlistInput): String {
        return buildString {
            // Include current date for temporal context
            appendLine("# Current Date")
            appendLine(java.time.LocalDate.now().toString())
            appendLine()
            
            appendLine("# Query")
            appendLine(input.query)
            appendLine()

            if (input.currentShortlist.isNotEmpty()) {
                appendLine("# Current Shortlist")
                input.currentShortlist.forEachIndexed { index, source ->
                    appendLine("## Source ${index + 1}")
                    appendLine("URL: ${source.url}")
                    appendLine("Source Classification: ${source.sourceClassification}")
                    appendLine("Content Date: ${source.contentDate ?: "Not found"}")
                    appendLine("Answer Type: ${source.answerType}")
                    appendLine("Relevance Justification: ${source.relevanceJustification}")
                    appendLine()
                    // Include first part of markdown for context
                    val previewLength = 500
                    val markdownPreview = if (source.markdown.length > previewLength) {
                        source.markdown.take(previewLength) + "..."
                    } else {
                        source.markdown
                    }
                    appendLine("Markdown Preview:")
                    appendLine(markdownPreview)
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }

            appendLine("# New Sources to Evaluate")
            input.newMarkdownBatch.forEachIndexed { index, source ->
                appendLine("## New Source ${index + 1}")
                appendLine("URL: ${source.url}")
                appendLine()
                appendLine("Markdown Content:")
                appendLine(source.markdown)
                appendLine()
                appendLine("---")
                appendLine()
            }

            appendLine()
            appendLine("# Instructions")
            appendLine("Classify the new sources and update the shortlist. You may:")
            appendLine("- Add valuable new sources to the shortlist")
            appendLine("- Remove less relevant existing sources if new sources are better")
            appendLine("- Keep existing sources if they remain valuable")
            appendLine("- Decide if the current shortlist is sufficient to answer the query comprehensively")
        }
    }

    private fun buildUrlToMarkdownMap(input: StreamingSourceShortlistInput): Map<String, String> {
        val map = mutableMapOf<String, String>()
        
        // Add existing shortlist markdowns
        input.currentShortlist.forEach { source ->
            map[source.url] = source.markdown
        }
        
        // Add new batch markdowns
        input.newMarkdownBatch.forEach { source ->
            map[source.url] = source.markdown
        }
        
        return map
    }
}

