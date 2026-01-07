package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.Tool
import com.google.genai.types.UrlContext
import io.deepsearch.domain.agents.FullQueryBreakdownInput
import io.deepsearch.domain.agents.FullQueryBreakdownOutput
import io.deepsearch.domain.agents.IFullQueryBreakdownAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.WebsiteContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * GenAI implementation of FullQueryBreakdownAgent.
 * Uses Gemini URL Context tool to fetch page content and extract context + requirements.
 * 
 * Reference: https://ai.google.dev/gemini-api/docs/url-context
 */
class FullQueryBreakdownAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IFullQueryBreakdownAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val urlContextTool = Tool.builder()
        .urlContext(UrlContext.builder().build())
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Website context extraction with query expansion, requirements, and follow-up queries")
        .properties(
            mapOf(
                "pageTitle" to Schema.builder()
                    .type("STRING")
                    .description("The title of the page")
                    .nullable(true)
                    .build(),
                "pageDescription" to Schema.builder()
                    .type("STRING")
                    .description("A brief description of the page from meta tags or content")
                    .nullable(true)
                    .build(),
                "contentSummary" to Schema.builder()
                    .type("STRING")
                    .description("Brief summary of what this webpage is about (1-2 sentences)")
                    .build(),
                "expandedQuery" to Schema.builder()
                    .type("STRING")
                    .description("The query rewritten with website context for clarity")
                    .build(),
                "fulfillmentRequirements" to Schema.builder()
                    .type("ARRAY")
                    .description("Atomic requirements that must ALL be satisfied to fully answer the query")
                    .items(
                        Schema.builder()
                            .type("STRING")
                            .description("A single atomic requirement")
                            .build()
                    )
                    .build(),
                "followUpQueries" to Schema.builder()
                    .type("ARRAY")
                    .description("Search queries to find information for each requirement")
                    .items(
                        Schema.builder()
                            .type("STRING")
                            .description("A search query")
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("contentSummary", "expandedQuery", "fulfillmentRequirements", "followUpQueries"))
        .build()

    private val systemInstruction = """
        You are the Query Breakdown agent with access to URL context
        
        ## Step 1: Extract Website Context
        - Use the URL context tool to extract the website content
        - Extract or infer the page title and description if available
        - Generate a comprehensive summary for the webpage
        
        ## Step 2: Expand the Query
        - Expand the input query, use the webpage context for reference
        
        Examples:
        - Query: "What's the pricing?" + Page about Stripe payment processing
          → "What are Stripe's pricing and fees for payment processing services?"
        - Query: "How do I get started?" + Page about AWS documentation
          → "How do I get started using AWS cloud services?"
        
        ## Step 3: Generate Fulfillment Requirements
        - Based on the expanded query and page context, generate requirements needed to fully answer the query
        
        Requirements must be:
        - Atomic: Each represents a single piece of information
        - Comprehensive: Together they fully cover what's needed
        - Minimal: No redundancy, only what's necessary
        - Specific: Clear statements of what information is needed
        
        ## Step 4: Generate Follow-up Queries
        - Generate search queries that would help find information for each requirement
        - These queries will be used for link discovery on the target website
        - Make them specific and actionable
        
        ## Output Format
        {
            "pageTitle": "Title of the page",
            "pageDescription": "Brief description from meta or content",
            "contentSummary": "Brief description of what this page is about",
            "expandedQuery": "Clear, context-aware version of the query",
            "fulfillmentRequirements": ["Requirement 1", "Requirement 2", ...],
            "followUpQueries": ["search query 1", "search query 2", ...]
        }
    """.trimIndent()

    @Serializable
    private data class QueryBreakdownResponse(
        val pageTitle: String? = null,
        val pageDescription: String? = null,
        val contentSummary: String,
        val expandedQuery: String,
        val fulfillmentRequirements: List<String>,
        val followUpQueries: List<String> = emptyList()
    )

    override suspend fun generate(input: FullQueryBreakdownInput): FullQueryBreakdownOutput {
        logger.debug(
            "Breaking down query with URL Context tool: query='{}', url='{}'",
            input.searchQuery.query,
            input.url
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        // Include the URL in the prompt - the URL Context tool will fetch it
        val userPrompt = buildUserPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<QueryBreakdownResponse>(this@FullQueryBreakdownAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    userPrompt,
                    GenerateContentConfig.builder()
                        .temperature(0F)
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .tools(listOf(urlContextTool))
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingBudget(0)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                        .build()
                )

                result.checkFinishReason()

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
            "Full query breakdown result: title='{}', summary='{}', expanded='{}', requirements={}, followUpQueries={}",
            response.pageTitle,
            response.contentSummary,
            response.expandedQuery,
            response.fulfillmentRequirements,
            response.followUpQueries
        )

        val websiteContext = WebsiteContext(
            url = input.url,
            title = response.pageTitle,
            description = response.pageDescription,
            contentSummary = response.contentSummary
        )

        return FullQueryBreakdownOutput(
            websiteContext = websiteContext,
            expandedQuery = response.expandedQuery,
            fulfillmentRequirements = response.fulfillmentRequirements,
            followUpQueries = response.followUpQueries,
            tokenUsage = tokenUsage
        )
    }

    private fun buildUserPrompt(input: FullQueryBreakdownInput): String = buildString {
        appendLine("# User Query")
        appendLine(input.searchQuery.query)
        appendLine()
        appendLine("# Target URL")
        appendLine(input.url)
    }
}
