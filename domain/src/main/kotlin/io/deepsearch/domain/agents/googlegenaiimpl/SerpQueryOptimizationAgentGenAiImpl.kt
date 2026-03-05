package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.ISerpQueryOptimizationAgent
import io.deepsearch.domain.agents.SerpQueryOptimizationInput
import io.deepsearch.domain.agents.SerpQueryOptimizationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SerpQueryOptimizationAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : ISerpQueryOptimizationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema =
        Schema.builder()
            .type("OBJECT")
            .description("Optimized search query for Google SERP")
            .properties(
                mapOf(
                    "optimized_query" to Schema.builder()
                        .type("STRING")
                        .description("The optimized search query string")
                        .build()
                )
            )
            .required(listOf("optimized_query"))
            .build()

    private val systemInstruction = """
        You are a Google Search Query Optimization agent. Your job is to transform verbose, natural language queries into concise, keyword-rich search queries that will perform well on Google Search.

        The user will provide:
        1. A natural language query (often verbose or conversational)
        2. A target website URL

        Your task:
        - Extract the core intent and key concepts from the query
        - Remove filler words, conversational phrases, and redundant terms
        - Preserve essential keywords and specific terms
        - Output a concise search query (typically 3-8 words) optimized for Google
        - Do NOT include site: operators - those will be added separately

        Examples:

        Input: "I want to find out what are all the different pricing plans and subscription options available for enterprise customers"
        Output: { "optimized_query": "enterprise pricing plans subscription options" }

        Input: "Can you help me understand how the API authentication works and what credentials I need to get started with the integration"
        Output: { "optimized_query": "API authentication credentials integration guide" }

        Input: "I'm looking for information about the company leadership team including the CEO and other executives"
        Output: { "optimized_query": "leadership team CEO executives" }

        Input: "What are the system requirements and technical specifications needed to run the software on my computer"
        Output: { "optimized_query": "system requirements technical specifications" }
    """.trimIndent()

    override suspend fun generate(input: SerpQueryOptimizationInput): SerpQueryOptimizationOutput {
        logger.debug("Optimizing query for SERP: {}", input.query)

        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val userPrompt = """
            Query: ${input.query}
            Target website: ${input.targetUrl}
        """.trimIndent()

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<SerpOptimizationResponse>(this@SerpQueryOptimizationAgentGenAiImpl::class.simpleName!!) {
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

        logger.debug("Optimized query: {} -> {}", input.query, response.optimizedQuery)

        return SerpQueryOptimizationOutput(
            optimizedQuery = response.optimizedQuery,
            tokenUsage = tokenUsage
        )
    }

    @Serializable
    private data class SerpOptimizationResponse(
        @kotlinx.serialization.SerialName("optimized_query")
        val optimizedQuery: String
    )
}

