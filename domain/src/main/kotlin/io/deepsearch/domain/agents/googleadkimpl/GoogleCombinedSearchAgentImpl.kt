package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.events.Event
import com.google.adk.runner.InMemoryRunner
import com.google.adk.tools.GoogleSearchTool
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import io.deepsearch.domain.agents.IGoogleCombinedSearchAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.tools.UrlContextTool
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.reactivex.rxjava3.core.Maybe
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An agent that enables BOTH Google Search and URL Context tools, so the model can
 * discover relevant pages and then fetch and reason over their content.
 *
 * Reference: URL context docs indicating combination with Google Search.
 * https://ai.google.dev/gemini-api/docs/url-context
 */
class GoogleCombinedSearchAgentImpl : IGoogleCombinedSearchAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("googleCombinedSearchAgent")
        description("Agent that combines Google Search tool and URL Context tool")
        model(ModelIds.GEMINI_2_5_LITE.modelId)
        tools(GoogleSearchTool(), UrlContextTool())
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.2F)
                .build()
        )
        instruction(
            (
                """
                You are a combined search agent. First, use Google Search tool to find the most relevant pages
                for the user's query and target site. Then use the URL Context tool to fetch those page(s)
                and answer using ONLY the retrieved content. Provide citations when possible.
                """
            ).trimIndent()
        )
        beforeToolCallback { invocationContext, baseTool, input, toolContext ->
            logger.debug("invoking tool {} with input {}", baseTool.name(), input)
            Maybe.empty()
        }
        build()
    }

    private val runner = InMemoryRunner(agent)

    override suspend fun generate(
        input: IGoogleCombinedSearchAgent.GoogleCombinedSearchInput
    ): IGoogleCombinedSearchAgent.GoogleCombinedSearchOutput {
        val (query, url) = input.searchQuery
        logger.debug("Combined search: '{}' on {}", query, url)

        val userPrompt = buildString {
            appendLine("$query")
        }

        val session = runner
            .sessionService()
            .createSession(
                this@GoogleCombinedSearchAgentImpl::class.simpleName,
                this@GoogleCombinedSearchAgentImpl::class.simpleName,
                null,
                null
            )
            .await()

        var finalEvent: Event? = null

        val eventsFlow = runner.runAsync(
            session,
            Content.fromParts(Part.fromText(userPrompt)),
            RunConfig.builder().apply {
                setStreamingMode(RunConfig.StreamingMode.NONE)
                setMaxLlmCalls(100)
            }.build()
        ).asFlow()

        eventsFlow.collect { event ->
            if (event.finalResponse()) {
                finalEvent = event
            }
        }

        var contentText = ""
        val eventContent = finalEvent?.content()
        if (eventContent?.isPresent == true) {
            val content = eventContent.get()
            if (content.parts().isPresent && content.parts().get().isNotEmpty()) {
                val first = content.parts().get()[0]
                if (first.text().isPresent) {
                    contentText = first.text().get()
                }
            }
        }

        val searchResult = SearchResult(
            originalQuery = input.searchQuery,
            content = contentText,
            // Sources will be inferred from the model response or can be enhanced later
            sources = listOf(url)
        )

        logger.debug("Combined search results: '{}' ...", contentText.take(200))

        return IGoogleCombinedSearchAgent.GoogleCombinedSearchOutput(searchResult)
    }
}


