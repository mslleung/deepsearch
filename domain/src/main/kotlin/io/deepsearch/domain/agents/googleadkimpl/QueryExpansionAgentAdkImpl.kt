package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.infra.ModelIds
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class QueryExpansionAgentAdkImpl : KoinComponent, IQueryExpansionAgent {

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

    private val queryExpansionAgent =
        LlmAgent.builder().run {
            name("queryExpansionAgent")
            description("Expand a user query into more specific queries")
            model(ModelIds.GEMINI_2_5_LITE.modelId)
            outputSchema(queryExpansionOutputSchema)
            disallowTransferToPeers(true)
            disallowTransferToParent(true)
            generateContentConfig(
                GenerateContentConfig.builder()
                    .temperature(0.2F)
                    .build()
            )
            instruction(
                """
            You are the Query Expansion agent. Your job is to transform the user's high-level query into a structured list of smaller, specific, and measurable queries.

            Output requirements:
            - Breakdown the input query into queries that are unique, atomic and unambiguous.
            - Avoid duplicates and near-duplicates; each query must have a distinct, non-overlapping purpose.
            - Order queries by importance, starting with the most important query.
            - Keep the number of queries as small as possible
            - If the user request is overly broad or practically unbounded, return queries targeting an overview or summary of the relevant results.

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
            )
            build()
        }

    private val runner = InMemoryRunner(queryExpansionAgent)

    override suspend fun generate(input: IQueryExpansionAgent.QueryExpansionAgentInput): IQueryExpansionAgent.QueryExpansionAgentOutput {
        logger.debug("Expand query: {}", input.query)

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
            Content.fromParts(Part.fromText(input.query)),
            RunConfig.builder().apply {
                setStreamingMode(RunConfig.StreamingMode.NONE)
                setMaxLlmCalls(100)
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

        val response = Json.decodeFromString<QueryExpansionResponse>(llmResponse)

        val expandedQueries = response.queries.map { it.query }

        logger.debug("Expanded queries: {}", expandedQueries)

        return IQueryExpansionAgent.QueryExpansionAgentOutput(expandedQueries = expandedQueries)
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