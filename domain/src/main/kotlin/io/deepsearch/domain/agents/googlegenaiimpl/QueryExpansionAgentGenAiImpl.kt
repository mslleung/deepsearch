package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.QueryExpansionAgentOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class QueryExpansionAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IQueryExpansionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val queryExpansionOutputSchema: Schema =
        Schema.builder()
            .type("OBJECT")
            .description("Structured output containing an ordered list of queries with rationales")
            .properties(
                mapOf(
                    "queries" to Schema.builder()
                        .type("ARRAY")
                        .description("At most 3 prioritized, atomic queries")
                        .items(
                            Schema.builder()
                                .type("OBJECT")
                                .properties(
                                    mapOf(
                                        "query" to Schema.builder()
                                            .type("STRING")
                                            .description("The executable query text")
                                            .build(),
                                        "rationale" to Schema.builder()
                                            .type("STRING")
                                            .description("Brief reason why this query is included")
                                            .build(),
                                    )
                                )
                                .required(listOf("query", "rationale"))
                                .build()
                        )
                        .build(),
                )
            )
            .required(listOf("queries"))
            .build()

    private val systemInstruction = """
        You are the Query Expansion agent. Your job is to transform the user's high-level query into a structured list of smaller, specific, and measurable queries.
        The queries will be be used to look for information in the company's website.

        Output requirements:
        - Breakdown the input query into queries that are unique, atomic and unambiguous.
        - Avoid duplicates and near-duplicates; each query must have a distinct, non-overlapping purpose.
        - Order queries by importance, starting with the most important query.
        - Keep the number of queries as small as possible
        - If the user request is overly broad or practically unbounded, return queries targeting an overview or summary of the relevant results.
        - Ignore input queries that are not related to knowledge retrieval such as "ok", "are you a bot?" etc.

        Example A:
        User query: "Find leadership info and headcount for the company"
        Expected shape:
        {
          "queries": [
            {
              "query": "leadership team",
              "rationale": "Locate official leadership page on the target site."
            },
            {
              "query": "company size/headcount",
              "rationale": "Search for an official mention of employee count."
            }
          ]
        }

        Example B (overly broad request):
        User query: "Show me all products on your ecommerce website"
        Expected shape:
        {
          "queries": [
            {
              "query": "Product overview",
              "rationale": "Provide an overview of available products"
            }
          ]
        }
    """.trimIndent()

    override suspend fun generate(input: io.deepsearch.domain.agents.QueryExpansionAgentInput): io.deepsearch.domain.agents.QueryExpansionAgentOutput {
        logger.debug("Expand query: {}", input.searchQuery)

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<QueryExpansionResponse>(this@QueryExpansionAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    input.searchQuery.query,
                    GenerateContentConfig.builder()
                        .temperature(0.2F)
                        .responseSchema(queryExpansionOutputSchema)
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

        val expandedQueries = response.queries.map { SearchQuery("${it.query} - ${it.rationale}", input.searchQuery.url) }

        logger.debug("Expanded queries: {}", expandedQueries)

        return QueryExpansionAgentOutput(
            expandedQueries = expandedQueries,
            tokenUsage = tokenUsage
        )
    }

    @Serializable
    private data class QueryExpansionResponse(
        val queries: List<Query>
    )

    @Serializable
    private data class Query(
        val query: String,
        val rationale: String
    )
}


