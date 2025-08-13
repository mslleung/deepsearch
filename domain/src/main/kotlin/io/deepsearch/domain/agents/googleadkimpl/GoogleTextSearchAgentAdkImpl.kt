package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.events.Event
import com.google.adk.runner.InMemoryRunner
import com.google.adk.tools.GoogleSearchTool
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GoogleSearch
import com.google.genai.types.Part
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An agent that uses Google ADK's built-in Google Search tool to fetch text snippets and sources.
 * The agent prompts the model to restrict results to the provided site and return a structured JSON.
 *
 * Reference: Google ADK Built-in tools – Google Search
 * https://google.github.io/adk-docs/tools/built-in-tools/#google-search
 */
class GoogleTextSearchAgentAdkImpl(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) :
    IGoogleTextSearchAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("googleTextSearchAgent")
        description("Agent to answer questions using Google Search")
        model(ModelIds.GEMINI_2_5_LITE.modelId) // Google Search tool supports Gemini 2 models
        // Register the built-in Google Search tool on the agent
        tools(GoogleSearchTool())
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.2F)
                .build()
        )
        instruction(
            ("""
                You are a Google Search agent. Use the Google Search tool to find information.
            """).trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    // Shared Ktor HTTP client with redirects disabled; we resolve redirects manually
    private val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                }
            }
        }
    }

    override suspend fun generate(
        input: IGoogleTextSearchAgent.GoogleTextSearchInput
    ): IGoogleTextSearchAgent.GoogleTextSearchOutput = coroutineScope {
        val (query, url) = input.searchQuery
        logger.debug("Google text search: '{}' on site {}", query, url)

        val userPrompt = buildString {
            appendLine("$query $url")
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

        var finalEventWithGrounding: Event? = null

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
                if (event.groundingMetadata().isPresent) {
                    finalEventWithGrounding = event
                }
            }
        }

        val groundingMetadata = finalEventWithGrounding!!.groundingMetadata().get()

        // 1) Collect all grounding chunk URIs and resolve redirects in parallel
        val groundingChunks = groundingMetadata.groundingChunks().orElse(listOf())
        val chunkIndexToResolvedUrl: Map<Int, String?> = coroutineScope {
            groundingChunks.mapIndexed { index, chunk ->
                val uriOpt = chunk.web().flatMap { web -> web.uri() }
                if (uriOpt.isPresent) {
                    async { index to resolveRedirectSafely(uriOpt.get()) }
                } else {
                    async { index to null }
                }
            }.awaitAll().toMap()
        }

        // 2) Get all grounding supports
        val supports = groundingMetadata.groundingSupports().orElse(listOf())

        // 3) Filter supports where at least one chunk URL starts with the requested site URL
        val relevantSupports = supports.filter { support ->
            val indices = support.groundingChunkIndices().orElse(listOf())
            indices.any { idx ->
                val resolved = chunkIndexToResolvedUrl[idx]
//                resolved != null && resolved.startsWith(url)
                resolved != null
            }
        }

        // TODO we may restrict to url prefix

        // 4) Concatenate the text from relevant supports and collect their sources
        val concatenatedText = relevantSupports.mapNotNull { support ->
            if (support.segment().isPresent) {
                val seg = support.segment().get()
                if (seg.text().isPresent) seg.text().get() else null
            } else null
        }.joinToString(" ")

        val relevantChunkIndices = relevantSupports.flatMap { support ->
            support.groundingChunkIndices().orElse(listOf())
        }.distinct()

        val sources = relevantChunkIndices.mapNotNull { idx ->
            chunkIndexToResolvedUrl[idx]
        }
//            .filter { source -> source.startsWith(url) }
            .distinct()

        val searchResult = SearchResult(
            originalQuery = input.searchQuery,
            content = concatenatedText,
            sources = sources
        )

        logger.debug("Google text search results: '{}' from sources {}", concatenatedText, sources)

        IGoogleTextSearchAgent.GoogleTextSearchOutput(searchResult)
    }

    private suspend fun resolveRedirectSafely(url: String): String {
        return try {
            val response = httpClient.head(url)
            if (response.status.isSuccess()) {
                return response.request.url.toString()
            }
            return url
        } catch (e: Exception) {
            logger.error("Failed to resolve redirect: {}", url, e)
            url
        }
    }
}


