package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.IQueryBreakdownAgent
import io.deepsearch.domain.agents.QueryBreakdownInput
import io.deepsearch.domain.agents.QueryBreakdownOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider

import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * GenAI implementation of QueryBreakdownAgent.
 * Uses website context to expand query and generate requirements.
 */
class QueryBreakdownAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IQueryBreakdownAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Query expansion with fulfillment requirements and follow-up queries")
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
        .required(listOf("expandedQuery", "fulfillmentRequirements", "followUpQueries"))
        .build()

    private val systemInstruction = """
        You are the Query Breakdown agent with access to website context.
        
        ## Session Continuation (if "Prior Session History" is provided)
        - Understand the prior conversation as a knowledge baseline
        - Focus on the current query, follow-up/expand/deepen the query as necessary
          to lead to the most appropriate or informative response
        
        ## Step 1: Expand the Query
        - Expand the input query, use the webpage context for reference
        
        Examples:
        - Query: "What's the pricing?" + Page about Stripe payment processing
          → "What are Stripe's pricing and fees for payment processing services?"
        - Query: "How do I get started?" + Page about AWS documentation
          → "How do I get started using AWS cloud services?"
        
        ## Step 2: Generate Fulfillment Requirements
        - Based on the expanded query and page context, generate requirements needed to fully answer the query
        - Only include requirements that directly answer what the user explicitly asked
        - Do NOT include nice-to-have context that wasn't explicitly requested
        
        Requirements must be:
        - Atomic: Each represents a single piece of information
        - Minimal: No redundancy, only what's necessary
        - Specific: Clear statements of what information is needed
        
        Example for "What's in the standard body check package?":
        - List of tests/screenings included in the standard package
        - Price of the standard package
        
        ## Step 3: Generate Follow-up Queries
        - Generate search queries that would help find information for each requirement
        - These queries will be used for link discovery on the target website
        - Make them specific and actionable
        
        ## Output Format
        {
            "expandedQuery": "Clear, context-aware version of the query",
            "fulfillmentRequirements": [
                "Requirement 1",
                "Requirement 2"
            ],
            "followUpQueries": ["search query 1", "search query 2", ...]
        }
    """.trimIndent()

    @Serializable
    private data class QueryBreakdownResponse(
        val expandedQuery: String,
        val fulfillmentRequirements: List<String>,
        val followUpQueries: List<String> = emptyList()
    )

    override suspend fun generate(input: QueryBreakdownInput): QueryBreakdownOutput {
        logger.debug(
            "Breaking down query with context: query='{}', context='{}', sessionHistorySize={}",
            input.searchQuery.query,
            input.websiteContext.toPromptSummary(),
            input.sessionHistory.sessions.size
        )

        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val userPrompt = buildUserPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<QueryBreakdownResponse>(this@QueryBreakdownAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    userPrompt,
                    GenerateContentConfig.builder()
                        .temperature(1.0F)
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
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
            "Query breakdown result: expanded='{}', requirements={}, followUpQueries={}",
            response.expandedQuery,
            response.fulfillmentRequirements,
            response.followUpQueries
        )

        return QueryBreakdownOutput(
            expandedQuery = response.expandedQuery,
            fulfillmentRequirements = response.fulfillmentRequirements,
            followUpQueries = response.followUpQueries,
            tokenUsage = tokenUsage
        )
    }

    private fun buildUserPrompt(input: QueryBreakdownInput): String = buildString {
        // Include session history first (for context prefix caching optimization)
        if (input.sessionHistory.isNotEmpty()) {
            appendLine("# Prior Session History")
            appendLine()
            input.sessionHistory.sessions.forEachIndexed { index, session ->
                appendLine("## Prior Session ${index + 1}")
                appendLine("Query: ${session.query}")
                appendLine()
                appendLine("Answer provided:")
                appendLine(session.answer)
                appendLine()
                if (index < input.sessionHistory.sessions.lastIndex) {
                    appendLine("---")
                    appendLine()
                }
            }
            appendLine("=" .repeat(50))
            appendLine()
        }
        
        appendLine("# Current Query")
        appendLine(input.searchQuery.query)
        appendLine()
        appendLine("# Website Context")
        appendLine("URL: ${input.websiteContext.url}")
        appendLine("Summary: ${input.websiteContext.contentSummary}")
    }
}
