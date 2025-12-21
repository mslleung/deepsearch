package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IPreviewShortlistAgent
import io.deepsearch.domain.agents.PreviewShortlistInput
import io.deepsearch.domain.agents.PreviewShortlistOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.models.valueobjects.PreviewShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.UrlContentResult
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Preview Shortlist agent that curates high-quality sources from HTML previews.
 * 
 * This agent is VERY RESTRICTIVE - it only shortlists sources that:
 * - Contain unambiguous prose content
 * - Do NOT have tables, grids, images, or icons that affect the answer
 * - Have high confidence that the information directly answers the query
 * 
 * If uncertain, the agent does NOT shortlist - the main path will handle it.
 */
class PreviewShortlistAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IPreviewShortlistAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val shortlistedSourceSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A shortlisted source with extracted facts and confidence")
        .properties(
            mapOf(
                "url" to Schema.builder().type("STRING")
                    .description("The URL of the source")
                    .build(),
                "extractedFacts" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("List of absolute facts extracted from clear prose content. Each fact should be a complete, standalone statement.")
                    .build(),
                "confidence" to Schema.builder().type("NUMBER")
                    .description("Confidence score (0.0-1.0) in the extracted facts. Must be >= 0.9 to be useful.")
                    .build(),
                "relevanceJustification" to Schema.builder().type("STRING")
                    .description("Brief reason for inclusion in shortlist")
                    .build()
            )
        )
        .required(listOf("url", "extractedFacts", "confidence", "relevanceJustification"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Updated shortlist of sources and confidence decision for answer synthesis")
        .properties(
            mapOf(
                "shortlist" to Schema.builder()
                    .type("ARRAY")
                    .items(shortlistedSourceSchema)
                    .description("List of shortlisted sources with extracted facts. Only include sources with high confidence.")
                    .build(),
                "isConfidentForAnswer" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether the shortlist contains sufficient high-confidence facts to answer the query. Only true if facts are unambiguous and complete.")
                    .build()
            )
        )
        .required(listOf("shortlist", "isConfidentForAnswer"))
        .build()

    private val systemInstruction = """
        You are a CONSERVATIVE source evaluation agent for early answer synthesis.
        You receive cleaned HTML from webpages and must extract ONLY absolute, unambiguous facts.

        CRITICAL RULES - You MUST follow these strictly:

        1. NEVER extract facts from tables, grids, or tabular data structures
           - <table>, <tr>, <td>, <th> elements → SKIP the source entirely
           - Repeated <div> patterns that look like rows/columns → SKIP
           - Pricing grids, comparison matrices, spec lists → SKIP

        2. NEVER extract facts that depend on images, icons, or visual elements
           - <img> tags present and relevant to answer → SKIP the source
           - Icon classes (fa-, bi-, material-icons, etc.) → SKIP if relevant to answer
           - Charts, diagrams, screenshots → SKIP the source

        3. ONLY extract facts from clear prose paragraphs
           - Well-formed sentences in <p>, <article>, <section>, or plain text
           - The meaning must be completely unambiguous from text alone
           - Examples: "Company X was founded in 2010", "The product costs $99/month", "Feature Y is available"

        4. HIGH CONFIDENCE THRESHOLD
           - Set confidence >= 0.9 ONLY if you are absolutely certain
           - If there's ANY ambiguity, set confidence < 0.7 (which means we don't use it)
           - When in doubt, DO NOT shortlist the source

        5. ANSWER SUFFICIENCY (isConfidentForAnswer)
           - Set true ONLY if you have extracted facts that DIRECTLY and COMPLETELY answer the query
           - Set false if facts are partial, uncertain, or incomplete
           - Set false if the query asks about something that typically requires tables/images (pricing, specs, comparisons)
           - When in doubt, set false - the main extraction pipeline will handle it

        Your goal is ACCURACY over COVERAGE. It is better to say "I cannot confidently extract" 
        than to extract an incorrect or ambiguous fact. The main multimodal extraction pipeline 
        will process everything properly - this is just an early exit optimization for simple content.

        Output Format:
        {
            "shortlist": [
                {
                    "url": "https://example.com/page",
                    "extractedFacts": ["Fact 1", "Fact 2"],
                    "confidence": 0.95,
                    "relevanceJustification": "Why this source is included"
                }
            ],
            "isConfidentForAnswer": true/false
        }
    """.trimIndent()

    @Serializable
    private data class LlmShortlistedSource(
        val url: String,
        val extractedFacts: List<String>,
        val confidence: Float,
        val relevanceJustification: String
    )

    @Serializable
    private data class ShortlistResponse(
        val shortlist: List<LlmShortlistedSource>,
        val isConfidentForAnswer: Boolean
    )

    override suspend fun generate(input: PreviewShortlistInput): PreviewShortlistOutput {
        logger.debug(
            "Generating preview shortlist for query: '{}', current shortlist size: {}, new batch size: {}",
            input.query,
            input.currentShortlist.size,
            input.newHtmlBatch.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.newHtmlBatch.isEmpty()) {
            logger.debug("Empty HTML batch, returning current shortlist")
            return PreviewShortlistOutput(
                updatedShortlist = input.currentShortlist,
                isConfidentForAnswer = false,
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

        // Convert LLM response to domain model, filtering low confidence sources
        val updatedShortlist = response.shortlist
            .filter { it.confidence >= 0.9f && it.extractedFacts.isNotEmpty() }
            .map { llmSource ->
                val title = input.newHtmlBatch.find { it.url == llmSource.url }?.title
                    ?: input.currentShortlist.find { it.url == llmSource.url }?.title

                PreviewShortlistedSource(
                    url = llmSource.url,
                    title = title,
                    extractedFacts = llmSource.extractedFacts,
                    confidence = llmSource.confidence,
                    relevanceJustification = llmSource.relevanceJustification
                )
            }

        logger.debug(
            "Preview shortlist updated: {} sources (filtered from {}), isConfidentForAnswer: {}",
            updatedShortlist.size,
            response.shortlist.size,
            response.isConfidentForAnswer
        )

        return PreviewShortlistOutput(
            updatedShortlist = updatedShortlist,
            isConfidentForAnswer = response.isConfidentForAnswer && updatedShortlist.isNotEmpty(),
            tokenUsage = tokenUsage
        )
    }

    private fun buildUserPrompt(input: PreviewShortlistInput): String {
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
                    appendLine("Confidence: ${source.confidence}")
                    appendLine("Extracted Facts:")
                    source.extractedFacts.forEach { fact ->
                        appendLine("  - $fact")
                    }
                    appendLine("Justification: ${source.relevanceJustification}")
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }

            appendLine("# New HTML Sources to Evaluate")
            input.newHtmlBatch.forEachIndexed { index, source ->
                appendLine("## New Source ${index + 1}")
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

            appendLine()
            appendLine("# Instructions")
            appendLine("Evaluate the new HTML sources and update the shortlist.")
            appendLine("Remember: Only extract facts from CLEAR PROSE. Skip any source with tables, images, or ambiguous content.")
            appendLine("Set isConfidentForAnswer=true ONLY if you have complete, high-confidence facts that directly answer the query.")
        }
    }
}
