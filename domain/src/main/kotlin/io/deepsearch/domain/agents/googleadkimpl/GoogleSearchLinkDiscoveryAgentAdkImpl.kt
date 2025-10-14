package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.events.Event
import com.google.adk.runner.InMemoryRunner
import com.google.adk.tools.GoogleSearchTool
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.GoogleSearchLinkDiscoveryInput
import io.deepsearch.domain.agents.GoogleSearchLinkDiscoveryOutput
import io.deepsearch.domain.agents.IGoogleSearchLinkDiscoveryAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An agent that uses Google ADK's built-in Google Search tool to discover relevant links.
 * Extracts URLs from grounding metadata and filters by URL prefix, returning URLs with their snippets.
 */
class GoogleSearchLinkDiscoveryAgentAdkImpl : IGoogleSearchLinkDiscoveryAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("googleSearchLinkDiscoveryAgent")
        description("Agent to discover links using Google Search")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        tools(GoogleSearchTool())
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
                You are a Google Search agent. Use the Google Search tool to find information.
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

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

    override suspend fun generate(input: GoogleSearchLinkDiscoveryInput): GoogleSearchLinkDiscoveryOutput {
        val (query, url) = input.searchQuery
        logger.debug("Google search link discovery: '{}' on site {}", query, url)

        val userPrompt = "$query $url"

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
                setMaxLlmCalls(1)
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

        // Collect all grounding chunk URIs and resolve redirects in parallel
        val groundingChunks = groundingMetadata.groundingChunks().orElse(listOf())
        val chunkIndexToData: Map<Int, Pair<String?, String?>> = coroutineScope {
            groundingChunks.mapIndexed { index, chunk ->
                async {
                    val uriOpt = chunk.web().flatMap { web -> web.uri() }
                    val titleOpt = chunk.web().flatMap { web -> web.title() }
                    
                    val resolvedUrl = if (uriOpt.isPresent) {
                        resolveRedirectSafely(uriOpt.get())
                    } else {
                        null
                    }
                    
                    val title = if (titleOpt.isPresent) titleOpt.get() else null
                    
                    index to (resolvedUrl to title)
                }
            }.awaitAll().toMap()
        }

        // Get all grounding supports
        val supports = groundingMetadata.groundingSupports().orElse(listOf())

        // Build a map from chunk index to snippet text
        val chunkIndexToSnippet = mutableMapOf<Int, String>()
        supports.forEach { support ->
            val indices = support.groundingChunkIndices().orElse(listOf())
            val snippetText = if (support.segment().isPresent) {
                val seg = support.segment().get()
                if (seg.text().isPresent) seg.text().get() else null
            } else null
            
            if (snippetText != null) {
                indices.forEach { idx ->
                    // Use first snippet for each chunk
                    if (!chunkIndexToSnippet.containsKey(idx)) {
                        chunkIndexToSnippet[idx] = snippetText
                    }
                }
            }
        }

        // Filter chunks where URL starts with the requested URL prefix
        val links = chunkIndexToData.mapNotNull { (idx, data) ->
            val (resolvedUrl, title) = data
            if (resolvedUrl != null && resolvedUrl.startsWith(url)) {
                val snippet = chunkIndexToSnippet[idx]
                val reason = when {
                    snippet != null -> snippet
                    title != null -> title
                    else -> "Found via Google Search"
                }
                
                WebpageLink(
                    url = resolvedUrl,
                    source = LinkSource.GOOGLE_SEARCH,
                    reason = reason
                )
            } else {
                null
            }
        }.distinctBy { it.url }

        logger.debug("Google search link discovery found {} links", links.size)

        return GoogleSearchLinkDiscoveryOutput(links)
    }

    private suspend fun resolveRedirectSafely(url: String): String {
        return try {
            val response = httpClient.head(url)
            response.request.url.toString()
        } catch (e: Exception) {
            logger.debug("Failed to resolve redirect for {}: {}", url, e.message)
            url
        }
    }
}

