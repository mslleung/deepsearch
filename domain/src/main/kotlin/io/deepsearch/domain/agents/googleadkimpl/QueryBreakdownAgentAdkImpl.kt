package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
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
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class QueryBreakdownAgentAdkImpl : IQueryBreakdownAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val queryBreakdownOutputSchema: Schema =
        Schema.builder()
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

    private val queryBreakdownAgent =
        LlmAgent.builder().run {
            name("queryBreakdownAgent")
            description("Break down a user query into atomic fulfillment requirements")
            model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
            outputSchema(queryBreakdownOutputSchema)
            disallowTransferToPeers(true)
            disallowTransferToParent(true)
            generateContentConfig(
                GenerateContentConfig.builder()
                    .temperature(0F)
                    .thinkingConfig(
                        ThinkingConfig.builder()
                            .thinkingBudget(0)
                            .build()
                    )
                    .build()
            )
            instruction(
                """
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
            )
            build()
        }

    private val runner = InMemoryRunner(queryBreakdownAgent)

    override suspend fun generate(input: QueryBreakdownAgentInput): QueryBreakdownAgentOutput {
        logger.debug("Breaking down query: {}", input.searchQuery)

        val response = retryLlmCall<QueryBreakdownResponse> {
            val session = runner
                .sessionService()
                .createSession(
                    this::class.simpleName,
                    this::class.simpleName, // placeholder, we will have userid later
                    null,
                    null
                )
                .await()

            val eventsFlow = runner.runAsync(
                session,
                Content.fromParts(Part.fromText(input.searchQuery.query)),
                RunConfig.builder().apply {
                    setStreamingMode(RunConfig.StreamingMode.NONE)
                    setMaxLlmCalls(1)
                }.build()
            ).asFlow()

            var llmResponse = ""
            eventsFlow.collect { event ->
                if (event.finalResponse() && event.content().isPresent) {
                    logger.debug("Received final model response (partial={})", event.partial().orElse(false))
                    val content = event.content().get()
                    if (content.parts().isPresent
                        && !content.parts().get().isEmpty()
                        && content.parts().get()[0].text().isPresent
                    ) {
                        if (!event.partial().orElse(false)) {
                            llmResponse = content.parts().get()[0].text().get()
                        }
                    }
                }
            }

            llmResponse
        }

        logger.debug("Breakdown points: {}", response.requirements)

        return QueryBreakdownAgentOutput(breakdownPoints = response.requirements)
    }

    @Serializable
    private data class QueryBreakdownResponse(
        val requirements: List<String>
    )
}

