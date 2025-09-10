package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import io.deepsearch.domain.agents.IGoogleUrlContextSearchAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.tools.UrlContextTool
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An agent that uses Google ADK with the URL Context tool to answer questions using the content
 * of a specific URL provided by the user.
 *
 * Reference: URL context tool – Google Gemini API
 * https://ai.google.dev/gemini-api/docs/url-context
 */
class GoogleUrlContextSearchAgentImpl : IGoogleUrlContextSearchAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("googleUrlContextSearchAgent")
        description("Agent to answer questions using URL Context tool on a specific page")
        model(ModelIds.GEMINI_2_5_LITE.modelId)
        // Register the custom URL Context tool so the model can fetch and use the page content
        tools(UrlContextTool())
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.2F)
                .build()
        )
        instruction(
            ("""
                You are a URL-context search agent. Use the URL Context tool to retrieve the provided URL(s)
                and answer the user's query using ONLY the content found at those URL(s).
                Provide a concise, factual answer. If information is not present, say you cannot find it on the page.
                """).trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    override suspend fun generate(
        input: io.deepsearch.domain.agents.GoogleUrlContextSearchInput
    ): io.deepsearch.domain.agents.GoogleUrlContextSearchOutput {
        val query = input.query
        val urls = input.urls

        logger.debug("URL-context search: '{}' on {} url(s)", query, urls.size)

        require(urls.count() <= 20) { "URL context can take at most 20 urls." }

        // Including the URLs directly in the prompt enables the URL Context tool to fetch them
        val userPrompt = buildString {
            appendLine("$query ${urls.joinToString(" ")}")
        }

        val session = runner
            .sessionService()
            .createSession(
                this@GoogleUrlContextSearchAgentImpl::class.simpleName,
                this@GoogleUrlContextSearchAgentImpl::class.simpleName,
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

        logger.debug("URL-context search results: '{}' from {} source(s)", llmResponse, urls.size)

        return io.deepsearch.domain.agents.GoogleUrlContextSearchOutput(llmResponse, urls)
    }
}
