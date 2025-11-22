package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GoogleSearch
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.Tool
import io.deepsearch.domain.agents.GoogleSearchLinkDiscoveryOutput
import io.deepsearch.domain.agents.IGoogleSearchLinkDiscoveryAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An agent that uses the Gemini API's native Google Search tool to discover relevant links.
 * The tool automatically performs Google searches and provides grounded results with citations.
 */
class GoogleSearchLinkDiscoveryAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IGoogleSearchLinkDiscoveryAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val searchTool = Tool.builder()
        .googleSearch(GoogleSearch.builder().build())
        .build()

    private val systemInstruction = """
        You are a Google Search agent specialized in link discovery. Use the Google Search tool to find relevant pages.
        Focus on returning the most authoritative and relevant results for the query.
    """.trimIndent()

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
        input: io.deepsearch.domain.agents.GoogleSearchLinkDiscoveryInput
    ): io.deepsearch.domain.agents.GoogleSearchLinkDiscoveryOutput {
        val (query, url) = input.searchQuery
        logger.debug("Google search link discovery: '{}' on site {}", query, url)

        val userPrompt = "$query $url"

        val response = client.models.generateContent(
            ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
            userPrompt,
            GenerateContentConfig.builder()
                .temperature(0.2F)
                .tools(listOf(searchTool))
                .thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(0)
                        .build()
                )
                .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                .build()
        )

        val groundingMetadata = response.candidates().orElse(listOf()).firstOrNull()?.groundingMetadata()

        if (groundingMetadata == null) {
            logger.warn("No grounding metadata in response")
            return GoogleSearchLinkDiscoveryOutput(links = emptyList())
        }

        // Collect all grounding chunk URIs and resolve redirects in parallel
        val groundingChunks = groundingMetadata.orElseThrow().groundingChunks().orElse(listOf())
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
        val supports = groundingMetadata.orElseThrow().groundingSupports().orElse(listOf())

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

        return GoogleSearchLinkDiscoveryOutput(links = links)
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


