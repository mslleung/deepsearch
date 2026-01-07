package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ISimpleQueryBreakdownAgent
import io.deepsearch.domain.agents.SimpleQueryBreakdownInput
import io.deepsearch.domain.agents.SimpleQueryBreakdownOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * GenAI implementation of SimpleQueryBreakdownAgent.
 * Uses cached website context to expand query and generate requirements.
 */
class SimpleQueryBreakdownAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : ISimpleQueryBreakdownAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Query expansion with fulfillment requirements")
        .properties(
            mapOf(
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
                    .build()
            )
        )
        .required(listOf("expandedQuery", "fulfillmentRequirements"))
        .build()

    private val systemInstruction = """
        You are the Query Breakdown agent with access to website context
        
        ## Step 1: Expand the Query
        - Expand the input query, use the webpage context for reference
        
        Examples:
        - Query: "What's the pricing?" + Page about Stripe payment processing
          → "What are Stripe's pricing and fees for payment processing services?"
        - Query: "How do I get started?" + Page about AWS documentation
          → "How do I get started using AWS cloud services?"
        
        ## Step 2: Generate Fulfillment Requirements
        - Based on the expanded query and page context, generate requirements needed to fully answer the query
        
        Requirements must be:
        - Atomic: Each represents a single piece of information
        - Comprehensive: Together they fully cover what's needed
        - Minimal: No redundancy, only what's necessary
        - Specific: Clear statements of what information is needed
        
        ## Output Format
        {
            "expandedQuery": "Clear, context-aware version of the query",
            "fulfillmentRequirements": ["Requirement 1", "Requirement 2", ...]
        }
    """.trimIndent()

    @Serializable
    private data class QueryBreakdownResponse(
        val expandedQuery: String,
        val fulfillmentRequirements: List<String>
    )

    override suspend fun generate(input: SimpleQueryBreakdownInput): SimpleQueryBreakdownOutput {
        logger.debug(
            "Breaking down query with cached context: query='{}', context='{}'",
            input.searchQuery.query,
            input.websiteContext.toPromptSummary()
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val userPrompt = buildUserPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<QueryBreakdownResponse>(this@SimpleQueryBreakdownAgentGenAiImpl::class.simpleName!!) {
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
            "Query breakdown result: expanded='{}', requirements={}",
            response.expandedQuery,
            response.fulfillmentRequirements
        )

        return SimpleQueryBreakdownOutput(
            expandedQuery = response.expandedQuery,
            fulfillmentRequirements = response.fulfillmentRequirements,
            tokenUsage = tokenUsage
        )
    }

    private fun buildUserPrompt(input: SimpleQueryBreakdownInput): String = buildString {
        appendLine("# User Query")
        appendLine(input.searchQuery.query)
        appendLine()
        appendLine("# Website Context")
        appendLine("URL: ${input.websiteContext.url}")
        input.websiteContext.title?.let { appendLine("Title: $it") }
        input.websiteContext.description?.let { appendLine("Description: $it") }
        input.websiteContext.contentSummary?.let { appendLine("Summary: $it") }
    }
}

