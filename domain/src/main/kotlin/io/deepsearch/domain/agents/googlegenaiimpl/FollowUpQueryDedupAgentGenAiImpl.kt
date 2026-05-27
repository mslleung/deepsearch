package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.IFollowUpQueryDedupAgent
import io.deepsearch.domain.agents.FollowUpQueryDedupInput
import io.deepsearch.domain.agents.FollowUpQueryDedupOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * LLM-based agent that deduplicates follow-up queries using semantic similarity.
 * 
 * This agent filters out queries that are semantically similar to already-searched queries,
 * using LLM understanding to detect duplicates that simple string matching would miss.
 */
class FollowUpQueryDedupAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IFollowUpQueryDedupAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Deduplicated follow-up queries")
        .properties(
            mapOf(
                "dedupedQueries" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("List of semantically unique candidate queries that should be searched")
                    .build()
            )
        )
        .required(listOf("dedupedQueries"))
        .build()

    private val systemInstruction = """
        You are a query deduplication agent. Your job is to filter out semantically duplicate queries.
        
        Given:
        1. The original user query
        2. A list of queries that have already been searched
        3. A list of candidate follow-up queries to consider
        
        Your task:
        - Identify which candidate queries are semantically unique and worth searching
        - Remove queries that are semantically similar to:
          - The original query
          - Any previously searched query
          - Other candidate queries (keep only one from each semantic group)
        
        Semantic similarity means the queries would likely return the same search results.
        
        Examples of duplicates to remove:
        - "stripe pricing" and "pricing for stripe" (same intent)
        - "API documentation" and "docs for the API" (same intent)
        - "enterprise features" and "features for enterprise customers" (same intent)
        
        Examples of distinct queries to keep:
        - "pricing plans" vs "API rate limits" (different topics)
        - "free tier" vs "enterprise pricing" (different tiers)
        - "authentication" vs "webhooks" (different features)
        
        Return only the candidate queries that are truly novel and should be searched.
        If all candidates are duplicates, return an empty array.
        
        Output Format:
        {
          "dedupedQueries": ["query1", "query2"]
        }
    """.trimIndent()

    @Serializable
    private data class DedupResponse(
        val dedupedQueries: List<String> = emptyList()
    )

    override suspend fun generate(input: FollowUpQueryDedupInput): FollowUpQueryDedupOutput {
        // Fast path: if no candidates, return empty
        if (input.candidateQueries.isEmpty()) {
            logger.debug("No candidate queries to deduplicate")
            return FollowUpQueryDedupOutput(
                dedupedQueries = emptyList(),
                tokenUsage = TokenUsageMetrics.empty(ModelIds.GEMINI_3_1_FLASH_LITE.modelId)
            )
        }

        logger.debug(
            "Deduplicating {} candidate queries against {} previously searched",
            input.candidateQueries.size,
            input.previouslySearchedQueries.size
        )

        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val userPrompt = buildString {
            appendLine("# Original Query")
            appendLine(input.originalQuery)
            appendLine()
            appendLine("# Previously Searched Queries")
            if (input.previouslySearchedQueries.isEmpty()) {
                appendLine("(none)")
            } else {
                input.previouslySearchedQueries.forEachIndexed { index, query ->
                    appendLine("${index + 1}. $query")
                }
            }
            appendLine()
            appendLine("# Candidate Follow-up Queries to Deduplicate")
            input.candidateQueries.forEachIndexed { index, query ->
                appendLine("${index + 1}. $query")
            }
        }

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<DedupResponse>(this@FollowUpQueryDedupAgentGenAiImpl::class.simpleName!!) {
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
            "Deduplicated {} candidates to {} unique queries",
            input.candidateQueries.size,
            response.dedupedQueries.size
        )

        return FollowUpQueryDedupOutput(
            dedupedQueries = response.dedupedQueries,
            tokenUsage = tokenUsage
        )
    }
}

