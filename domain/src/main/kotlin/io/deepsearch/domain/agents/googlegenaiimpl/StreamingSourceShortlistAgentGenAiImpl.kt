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
        .description("A shortlisted source with classification metadata, reasoning, and relevant images")
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
                    .build(),
                "relevantImageIds" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("List of image IDs (numbered, e.g., '1', '2') from this source that are relevant to answering the query. Only include truly useful images like product screenshots, diagrams, or charts. Most sources should return an empty array.")
                    .build()
            )
        )
        .required(listOf("url", "sourceClassification", "answerType", "relevanceJustification", "relevantImageIds"))
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
        
        Input: 
        - Current date
        - User query
        - Current shortlist
        - New sources for shortlist evaluation/update
           -- Sources marked as "PREVIEW" contain fast simple text extraction from a webpage HTML
           -- Preview content is useful for quick relevance assessment but tables are broken and images/icons are missing
           -- If a preview source appears relevant to the query, you should shortlist it - the full markdown will arrive later
           -- Do not reject a source solely because it is a preview if the text suggests it may contain the answer
           -- When the full markdown arrives for a previously shortlisted preview, it will automatically replace the preview content for you to assess again
        
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
        
        4. **Select Relevant Images** (relevantImageIds):
           - Sources may contain images in format: `<image id="N">description</image>` or `<image id="N" placeholder>alt text</image>`
           - For each source, identify images that would genuinely help answer the query (e.g., product screenshots, pricing tables, feature diagrams, charts)
           - Return an array of image IDs (the number only, e.g., ["1", "3"]) for images that add value
           - Most sources should return an EMPTY array [] - only include images that are truly necessary
           - Do NOT include decorative images, logos, icons, or generic stock photos
           - If you select a placeholder image as relevant, you MUST set isGoodEnough=false to wait for the full image
        
        5. **Determine shortlist sufficiency** (isGoodEnough decision):
           - Set isGoodEnough=false if the best source is an `OFFICIAL_SNAPSHOT` (Blog/News) when the query asks for static facts (Price, Specs, Current Features), unless you have verified no Living Doc exists.
           - Set isGoodEnough=false if the answer relies on data that is too old.
           - **ALWAYS** set `isGoodEnough=false` if the source suggests a "New Update" but the URL indicates a blog post (e.g., /blog/pricing-update), as we must find the actual implementation page.
           - **ALWAYS** set isGoodEnough=false if any selected relevant image is a placeholder (we need to wait for the full markdown with the actual image)
           - When isGoodEnough=false, the pipeline will fetch more sources for the next shortlist generation. 
             Since sources are not given to you in any order, prefer to process more sources if the sources seem to
             be biased and not directly answer the query.
           - Set isGoodEnough=true only when you have high-quality, current sources that directly answer the query.
        
        Shortlist Management:
        - Maximum ~20 sources in shortlist (remove less relevant ones if needed)
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
              "relevanceJustification": "Why this source is included",
              "relevantImageIds": ["1", "2"]
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
        val relevanceJustification: String,
        val relevantImageIds: List<String> = emptyList()
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

        // Build URL to source info map with transformed markdowns and image ID mappings
        val urlToSourceInfo = buildUrlToSourceInfoMap(input)

        // Build user prompt with query, current shortlist, and new sources (using transformed markdown)
        val userPrompt = buildUserPrompt(input, urlToSourceInfo)

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

        // Track if any selected images are placeholders (we need to wait for full markdown)
        var hasPlaceholderSelected = false

        // Convert LLM response to domain model
        val updatedShortlist = response.shortlist.mapNotNull { llmSource ->
            val sourceInfo = urlToSourceInfo[llmSource.url]
            if (sourceInfo == null) {
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

                // Check if any selected images are placeholders
                if (hasPlaceholderImages(llmSource.relevantImageIds, sourceInfo.imageIdMapping)) {
                    hasPlaceholderSelected = true
                    logger.debug("Source {} has placeholder image selected, will set isGoodEnough=false", llmSource.url)
                }

                // Map numbered image IDs back to original hash-based IDs
                val originalImageIds = mapNumberedIdsToOriginal(llmSource.relevantImageIds, sourceInfo.imageIdMapping)
                
                ShortlistedSource(
                    url = llmSource.url,
                    markdown = sourceInfo.markdown,
                    sourceClassification = sourceType,
                    contentDate = llmSource.contentDate,
                    answerType = answerType,
                    relevanceJustification = llmSource.relevanceJustification,
                    isPreview = sourceInfo.isPreview,
                    relevantImageIds = originalImageIds
                )
            }
        }

        // Force isGoodEnough=false if any selected images are placeholders
        val finalIsGoodEnough = if (hasPlaceholderSelected) {
            logger.debug("Forcing isGoodEnough=false because placeholder images were selected")
            false
        } else {
            response.isGoodEnough
        }

        val finalReason = if (hasPlaceholderSelected && response.isGoodEnough) {
            "${response.reason} [Waiting for full markdown with selected images]"
        } else {
            response.reason
        }

        logger.debug(
            "Shortlist updated: {} sources, isGoodEnough: {}, reason: {}",
            updatedShortlist.size,
            finalIsGoodEnough,
            finalReason
        )

        return StreamingSourceShortlistOutput(
            updatedShortlist = updatedShortlist,
            isGoodEnough = finalIsGoodEnough,
            reason = finalReason,
            tokenUsage = tokenUsage
        )
    }

    private fun buildUserPrompt(input: StreamingSourceShortlistInput, urlToSourceInfo: Map<String, SourceInfo>): String {
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
                    val sourceInfo = urlToSourceInfo[source.url]
                    appendLine("## Source ${index + 1}")
                    appendLine("URL: ${source.url}")
                    if (source.isPreview) {
                        appendLine("**Content Type: PREVIEW** (simple text only, tables/images/formatting not yet extracted)")
                    }
                    appendLine("Source Classification: ${source.sourceClassification}")
                    appendLine("Content Date: ${source.contentDate ?: "Not found"}")
                    appendLine("Answer Type: ${source.answerType}")
                    appendLine("Relevance Justification: ${source.relevanceJustification}")
                    if (source.relevantImageIds.isNotEmpty()) {
                        appendLine("Previously Selected Images: ${source.relevantImageIds.joinToString(", ")}")
                    }
                    appendLine()
                    // Include first part of transformed markdown for context (with numbered image IDs)
                    val previewLength = 500
                    val transformedMarkdown = sourceInfo?.transformedMarkdown ?: source.markdown
                    val markdownPreview = if (transformedMarkdown.length > previewLength) {
                        transformedMarkdown.take(previewLength) + "..."
                    } else {
                        transformedMarkdown
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
                val sourceInfo = urlToSourceInfo[source.url]
                appendLine("## New Source ${index + 1}")
                appendLine("URL: ${source.url}")
                if (source.isPreview) {
                    appendLine("**Content Type: PREVIEW** (simple text only, tables/images/formatting not yet extracted)")
                }
                appendLine()
                appendLine("Markdown Content:")
                // Use transformed markdown with numbered image IDs
                appendLine(sourceInfo?.transformedMarkdown ?: source.markdown)
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
            appendLine("- Select relevant images for each source (use the numbered image IDs)")
            appendLine("- Decide if the current shortlist is sufficient to answer the query comprehensively")
        }
    }

    /** Holds markdown content, preview status, and image ID mapping for a source */
    private data class SourceInfo(
        val markdown: String,
        val isPreview: Boolean,
        val transformedMarkdown: String,
        /** Maps numbered image ID (e.g., "1") to original ID (e.g., "img-xxx") or PLACEHOLDER_MARKER */
        val imageIdMapping: Map<String, String>
    )

    private fun buildUrlToSourceInfoMap(input: StreamingSourceShortlistInput): Map<String, SourceInfo> {
        val map = mutableMapOf<String, SourceInfo>()
        
        // Add existing shortlist markdowns (preserve their preview status)
        input.currentShortlist.forEach { source ->
            val (transformedMarkdown, imageMapping) = transformImageIdsForLlm(source.markdown)
            map[source.url] = SourceInfo(source.markdown, source.isPreview, transformedMarkdown, imageMapping)
        }
        
        // Add new batch markdowns (may upgrade preview to full markdown)
        input.newMarkdownBatch.forEach { source ->
            val existing = map[source.url]
            // If upgrading from preview to full markdown, update; otherwise add new
            if (existing == null || (existing.isPreview && !source.isPreview)) {
                val (transformedMarkdown, imageMapping) = transformImageIdsForLlm(source.markdown)
                map[source.url] = SourceInfo(source.markdown, source.isPreview, transformedMarkdown, imageMapping)
            }
        }
        
        return map
    }

    companion object {
        /** Marker used to identify placeholder images in the mapping */
        const val PLACEHOLDER_MARKER = "__PLACEHOLDER__"

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

        /**
         * Checks if any of the selected image IDs are placeholders.
         *
         * @param numberedIds List of numbered IDs selected by the LLM
         * @param imageMapping Mapping from numbered ID to original ID or PLACEHOLDER_MARKER
         * @return true if at least one selected image is a placeholder
         */
        fun hasPlaceholderImages(numberedIds: List<String>, imageMapping: Map<String, String>): Boolean {
            return numberedIds.any { numberedId ->
                imageMapping[numberedId] == PLACEHOLDER_MARKER
            }
        }
    }
}

