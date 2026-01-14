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
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Source Evaluation agent that evaluates preview content and extracts facts.
 * 
 * The input is now extracted sentences (plain text) instead of HTML, which:
 * - Is token-efficient (plain text vs verbose HTML)
 * - Naturally filters out tabular data (table cells are fragments, not sentences)
 * - Works across languages via ICU4J sentence detection
 * 
 * For the source, the agent:
 * - Determines the intention (purpose) of the webpage
 * - Assesses relevance to the query
 * - Extracts facts relevant to the query
 * 
 * This is a stateless agent that processes one source at a time, enabling parallel processing.
 * Used in the preview path for early exit with conservative fact extraction.
 */
class HtmlSourceEvalAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IHtmlSourceEvalAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Evaluated source with intention and extracted facts")
        .properties(
            mapOf(
                "intention" to Schema.builder().type("STRING")
                    .description("Describes the purpose of the webpage (e.g., 'Official pricing page showing subscription tiers', 'Blog post announcing new features from March 2024', 'Third-party review comparing products')")
                    .build(),
                "contentDate" to Schema.builder().type("STRING")
                    .description("Date extracted from content (null/empty if no date found)")
                    .nullable(true)
                    .build(),
                "relevantFacts" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("List of facts extracted from the source that are relevant to the query. Each fact should be a complete, standalone statement. Empty if page is not relevant.")
                    .build()
            )
        )
        .required(listOf("intention", "relevantFacts"))
        .build()

    private val systemInstruction = $$"""
        You are a source evaluation and fact extraction agent. Your job is to understand the purpose of a webpage and extract relevant facts from its content.
        
        Input: 
        - Current date
        - User query (with site: context)
        - Source metadata (URL, title, description)
        - Extracted text content (sentences extracted from the page)     
        
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
           - Look for dates mentioned in the text
           - If no date is found, leave it null/empty
        
        3. **Extract Relevant Facts** (relevantFacts):
           - Extract facts that are directly relevant to answering the query and address the requirements
           - Facts should be very detailed, providing as much information or supporting evidence if possible
           - All relevant facts must be extracted with no omission
           - Each fact should be a complete, standalone statement
           - If the page is not relevant, return an empty array
        
        Output Format:
        {
          "intention": "Official pricing page showing current subscription tiers",
          "contentDate": "2024-07-25",
          "relevantFacts": [
            "The Pro plan costs $99/month and includes up to 10 team members",
            "Enterprise pricing requires contacting sales for a custom quote"
          ]
        }
    """.trimIndent()

    @Serializable
    private data class EvalResponse(
        val intention: String,
        val contentDate: String? = null,
        val relevantFacts: List<String> = emptyList()
    )

    override suspend fun generate(input: HtmlSourceEvalInput): HtmlSourceEvalOutput {
        val source = input.htmlSource
        val queryWithSite = "${input.expandedQuery} site:${extractDomain(source.url)}"
        
        logger.debug(
            "Evaluating source for query: '{}', url: {}",
            queryWithSite,
            source.url
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
            "[{}] response for {}: intention='{}', facts={}",
            HtmlSourceEvalAgentGenAiImpl::class.simpleName,
            source.url,
            response.intention,
            response.relevantFacts.size
        )

        // Convert string facts to RelevantFact objects
        val facts = response.relevantFacts.map { RelevantFact(fact = it) }

        // If no facts found, return null
        if (facts.isEmpty()) {
            logger.debug("Source {} has no relevant facts, returning null", source.url)
            return HtmlSourceEvalOutput(
                evaluatedSource = null,
                tokenUsage = tokenUsage
            )
        }

        val evaluatedSource = EvaluatedSource(
            url = source.url,
            title = source.title,
            description = source.description,
            relevantFacts = facts,
            contentDate = response.contentDate,
            intention = response.intention,
            relevantImageIds = emptyList(), // Preview path doesn't handle images
            isPreview = true
        )

        logger.debug(
            "Source evaluation complete for {}: {} facts",
            source.url,
            facts.size
        )

        return HtmlSourceEvalOutput(
            evaluatedSource = evaluatedSource,
            tokenUsage = tokenUsage
        )
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
        val queryWithSite = "${input.expandedQuery} site:${extractDomain(input.htmlSource.url)}"
        val source = input.htmlSource
        return buildString {
            // Include current date for temporal context
            appendLine("# Current Date")
            appendLine(java.time.LocalDate.now().toString())
            appendLine()

            appendLine("# Query")
            appendLine(queryWithSite)
            appendLine()

            // Include fulfillment requirements if available
            if (input.fulfillmentRequirements.isNotEmpty()) {
                appendLine("# Fulfillment Requirements")
                input.fulfillmentRequirements.forEachIndexed { index, req ->
                    appendLine("${index + 1}. $req")
                }
                appendLine()
            }

            appendLine("# Source to Evaluate")
            appendLine("URL: ${source.url}")
            if (source.title != null) {
                appendLine("Title: ${source.title}")
            }
            if (source.description != null) {
                appendLine("Description: ${source.description}")
            }
            appendLine()
            appendLine("## Content")
            appendLine(source.cleanedHtml)  // This now contains extracted sentences, not HTML
        }
    }
}
