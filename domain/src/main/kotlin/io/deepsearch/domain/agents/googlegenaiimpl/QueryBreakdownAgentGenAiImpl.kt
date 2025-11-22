package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IQueryBreakdownAgent
import io.deepsearch.domain.agents.QueryBreakdownAgentInput
import io.deepsearch.domain.agents.QueryBreakdownAgentOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class QueryBreakdownAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IQueryBreakdownAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Structured output containing a list of query fulfillment requirements")
        .properties(
            mapOf(
                "requirements" to Schema.builder()
                    .type("ARRAY")
                    .description("Minimal, comprehensive, atomic fulfillment requirements for the query")
                    .items(
                        Schema.builder()
                            .type("STRING")
                            .description("A single atomic requirement that must be addressed")
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("requirements"))
        .build()

    private val systemInstruction = """
        You are the Query Breakdown agent. Your job is to break down the user's query into a list of minimal, comprehensive, and atomic fulfillment requirements.
        Only if ALL these requirements are addressed should the query be considered answered.
        
        Output requirements:
        - Each requirement must be atomic - it should represent a single, indivisible piece of information needed
        - Requirements must be comprehensive - together they should fully cover what's needed to answer the query
        - Requirements must be minimal - avoid redundancy, include only what's necessary
        - Each requirement should be a clear, specific statement of what information is needed
        - Avoid duplicates and near-duplicates; each requirement must have a distinct, non-overlapping purpose.
        - Keep the total number of requirements as small as possible while maintaining completeness
        - If the query is overly broad or practically unbounded, return queries targeting an overview or summary of the relevant results.
        
        Example A:
        User query: "Find leadership info and headcount for the company"
        Expected output:
        {
          "requirements": [
            "Information about the leadership team",
            "Company headcount"
          ]
        }
        
        Example B:
        User query: "What are your product pricing plans?"
        Expected output:
        {
          "requirements": [
            "List of available pricing plans or tiers",
            "Price for each plan",
            "Features included in each plan"
          ]
        }
        
        Example C:
        User query: "Tell me about your company"
        Expected output:
        {
          "requirements": [
            "Company mission or purpose",
            "Core products or services offered",
            "Company founding date and history overview"
          ]
        }
        
        Example D (overly broad request):
        User query: "Show me everything about your products"
        Expected output:
        {
          "requirements": [
            "Overview of main product categories",
            "Key features of primary products",
            "Product availability or access information"
          ]
        }
    """.trimIndent()

    @Serializable
    private data class QueryBreakdownResponse(
        val requirements: List<String>
    )

    override suspend fun generate(input: QueryBreakdownAgentInput): QueryBreakdownAgentOutput {
        logger.debug("Breaking down query: '{}'", input.searchQuery.query)

        val response = retryLlmCall<QueryBreakdownResponse> {
            val result = client.models.generateContent(
                ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
                input.searchQuery.query,
                GenerateContentConfig.builder()
                    .temperature(0F)
                    .responseSchema(outputSchema)
                    .thinkingConfig(
                        ThinkingConfig.builder()
                            .thinkingBudget(0)
                            .build()
                    )
                    .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                    .build()
            )

            result.checkFinishReason()

            result.text() ?: throw RuntimeException("No text response from model")
        }

        logger.debug("Breakdown points: {}", response.requirements)

        return QueryBreakdownAgentOutput(breakdownPoints = response.requirements)
    }
}


