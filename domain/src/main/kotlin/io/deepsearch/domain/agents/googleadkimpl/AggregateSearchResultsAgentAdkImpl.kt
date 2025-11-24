package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.AggregateSearchResultsInput
import io.deepsearch.domain.agents.AggregateSearchResultsOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
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
        .description("Aggregated answer for the user's query")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Concise, faithful response that answers the original query by aggregating information from multiple search results")
                    .build()
            )
        )
        .required(listOf("answer"))
        .build()

    private val agent = LlmAgent.builder().run {
        name("aggregateSearchResultsAgent")
        description("Aggregate multiple search results into a single, concise answer against the user query")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        outputSchema(outputSchema)
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
            ("""
                You are the Aggregate Search Results agent. Given the user's original query and a set of search results (each with sub-query and answer), produce a single, coherent answer that answers the user's query.

                Instructions:
                - Directly address the user's original query
                - Be succinct, focus on a brief and complete answer.
                - Is faithful to the provided results only (do not invent facts)
                - If information conflicts, note the discrepancy comprehensively
                - Do not include or mention irrelevant results
                - If the results are insufficient to answer, state that no relevant information was found

                Output format:
                - Return ONLY a valid JSON object with a single field "answer": {"answer": "your aggregated answer text"}

                Example (input and output):
                **Input:**
                Original query: "Tell me about your company" for site https://www.google.com
                Search results to aggregate (JSON):
                [
                  {
                    "subquery": "company description",
                    "answer": "Google is a global technology company most famous for its dominant internet search engine, which organizes the world's information.",
                  },
                  {
                    "subquery": "company history",
                    "answer": "Google is an American multinational technology company that was founded in 1998 by Larry Page and Sergey Brin while they were students at Stanford University. In 2015, Google became a subsidiary of the holding company Alphabet Inc., and it continues to serve as the umbrella for Alphabet's internet-related interests.",
                  }
                ]

                **Output:**
                {
                  "answer": "Google is a global technology company most famous for its dominant internet search engine, which organizes the world's information. It was founded in 1998 by Larry Page and Sergey Brin while they were students at Stanford University. In 2015, Google became a subsidiary of the holding company Alphabet Inc., and it continues to serve as the umbrella for Alphabet's internet-related interests."
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
            appendLine("Search results to aggregate (JSON):")
            
            // Build proper JSON array
            val jsonResults = input.searchResults.map { searchResult ->
                SearchResultForPrompt(
                    subquery = searchResult.originalQuery.query,
                    answer = searchResult.answer
                )
            }
            appendLine(Json.encodeToString(jsonResults))
        }

        val response = retryLlmCall<AggregatedResultResponse> {
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
                    setMaxLlmCalls(1)
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

            llmResponse
        }

        // Collect all unique answer sources from input search results
        val allAnswerSources = input.searchResults
            .flatMap { it.answerSources }
        
        // Collect all explored sources
        val allExploredSources = input.searchResults
            .flatMap { it.exploredSources }
            .distinct()
        
        // Combine all content from input search results
        val allContent = input.searchResults.joinToString("\n\n---\n\n") { it.content }

        val aggregated = SearchResult(
            originalQuery = input.searchQuery,
            answer = response.answer,
            content = allContent,
            answerSources = allAnswerSources,
            exploredSources = allExploredSources
        )
        logger.debug(
            "Aggregated answer length: {} chars, answerSources: {}, exploredSources: {}",
            aggregated.answer.length,
            aggregated.answerSources.size,
            aggregated.exploredSources.size
        )
        return AggregateSearchResultsOutput(aggregatedResult = aggregated,
            tokenUsage = TokenUsageMetrics.empty(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId))
    }

    @Serializable
    private data class SearchResultForPrompt(
        val subquery: String,
        val answer: String,
    )

    @Serializable
    private data class AggregatedResultResponse(
        val answer: String
    )
}


