package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent.AggregateSearchResultsInput
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent.AggregateSearchResultsOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.models.valueobjects.SearchResult
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AggregateSearchResultsAgentAdkImpl : IAggregateSearchResultsAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema = Schema.builder()
        .type("OBJECT")
        .description("Aggregated search result for the user's query")
        .properties(
            mapOf(
                "originalQuery" to Schema.builder()
                    .type("STRING")
                    .description("The original search query text")
                    .build(),
                "content" to Schema.builder()
                    .type("STRING")
                    .description("Concise, faithful response that answers the query")
                    .build(),
                "sources" to Schema.builder()
                    .type("ARRAY")
                    .description("List of cited sources used in the response")
                    .items(
                        Schema.builder()
                            .type("STRING")
                            .build()
                    )
                    .build(),
            )
        )
        .required(listOf("originalQuery", "content", "sources"))
        .build()

    private val agent = LlmAgent.builder().run {
        name("aggregateSearchResultsAgent")
        description("Aggregate multiple search results into a single, concise answer against the user query")
        model(ModelIds.GEMINI_2_5_LITE.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.1F)
                .build()
        )
        instruction(
            ("""
                You are the Aggregate Search Results agent. Given the user's original query and a set of search results (each with text and sources), produce a single, coherent answer that:
                - Is faithful to the provided results only (do not invent facts)
                - Directly addresses the user's original query
                - Do not include irrelevant results
                - If information conflicts, note the discrepancy comprehensively with reference to the source.
                - If the results are insufficient to answer, just say no relevant information found and return empty source.
                - Preserve important figures, names, and exact quotes where relevant
                - Cite only the sources of the input search results that are included in the final aggregated search result

                Output format:
                - Return ONLY a valid JSON object exactly matching the output schema fields: {"originalQuery", "content", "sources"}.

                Example (input and output):
                **Input:**
                Original query: "Tell me about your company" for site https://www.google.com
                Search results to aggregate (JSON-like list):
                - Result 1:
                  subquery: "company description"
                  content: "Google is a global technology company most famous for its dominant internet search engine, which organizes the world's information."
                  sources: ["https://www.google.com"]
                - Result 2:
                  subquery: "company description"
                  content: "Google is an American multinational technology company that was founded in 1998 by Larry Page and Sergey Brin while they were students at Stanford University. In 2015, Google became a subsidiary of the holding company Alphabet Inc., and it continues to serve as the umbrella for Alphabet's internet-related interests."
                  sources: ["https://www.google.com"]

                **Output:**
                {
                  "originalQuery": "Tell me about your company",
                  "content": "The page is an illustrative example site explaining that example.com is reserved for documentation and examples.",
                  "sources": ["https://www.google.com"]
                }
                """).trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    override suspend fun generate(input: AggregateSearchResultsInput): AggregateSearchResultsOutput {
        logger.debug("Aggregate {} results for query: {}", input.searchResults.size, input.searchQuery)

        val userPrompt = buildString {
            appendLine("Original query: \"${input.searchQuery.query}\" for site ${input.searchQuery.url}")
            appendLine()
            appendLine("Search results to aggregate (JSON-like list):")
            input.searchResults.forEachIndexed { index, searchResult ->
                appendLine("- Result ${index + 1}:")
                appendLine("  subquery: \"${searchResult.originalQuery.query}\"")
                appendLine("  content: \"" + searchResult.content.replace("\n", " ").replace("\"", "\\\"") + "\"")
                appendLine("  sources: [" + searchResult.sources.joinToString(", ") { "\"" + it + "\"" } + "]")
            }
        }

        val session = runner
            .sessionService()
            .createSession(
                this::class.simpleName,
                this::class.simpleName,
                null,
                null
            )
            .await()

        var llmResponse = ""

        val eventsFlow = runner.runAsync(
            session,
            Content.fromParts(Part.fromText(userPrompt)),
            RunConfig.builder().apply {
                setStreamingMode(RunConfig.StreamingMode.NONE)
                setMaxLlmCalls(100)
            }.build()
        ).asFlow()

        eventsFlow.collect { event ->
            if (event.finalResponse() && event.content().isPresent) {
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

        val response = Json.decodeFromString<AggregatedResultResponse>(llmResponse)

        val aggregated = SearchResult(
            originalQuery = input.searchQuery,
            content = response.content,
            sources = response.sources
        )
        logger.debug(
            "Aggregated content length: {} sources: {}",
            aggregated.content,
            aggregated.sources
        )
        return AggregateSearchResultsOutput(aggregatedResult = aggregated)
    }

    @Serializable
    private data class AggregatedResultResponse(
        val originalQuery: String,
        val content: String,
        val sources: List<String>
    )
}


