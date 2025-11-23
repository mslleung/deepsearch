package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GoogleSearch
import com.google.genai.types.Part
import com.google.genai.types.Tool
import io.deepsearch.domain.agents.GoogleTextSearchOutput
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An agent that uses Google GenAI SDK's native Google Search tool to fetch text snippets and sources.
 */
class GoogleTextSearchAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IGoogleTextSearchAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val searchTool = Tool.builder()
        .googleSearch(GoogleSearch.builder().build())
        .build()

    private val systemInstruction = """
        You are a Google Search agent. Use the Google Search tool to find information.
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
        input: io.deepsearch.domain.agents.GoogleTextSearchInput
    ): GoogleTextSearchOutput {
        val (query, url) = input.searchQuery
        logger.debug("Google text search: '{}' on site {}", query, url)

        val userPrompt = buildString {
            appendLine("$query $url")
        }

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = client.models.generateContent(
            modelId,
            userPrompt,
            GenerateContentConfig.builder()
                .temperature(0.2F)
                .tools(listOf(searchTool))
                .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                .build()
        )
        
        // Extract token usage
        response.usageMetadata().ifPresent { metadata ->
            tokenUsage = TokenUsageMetrics(
                modelName = modelId,
                promptTokens = metadata.promptTokenCount().orElse(0),
                outputTokens = metadata.candidatesTokenCount().orElse(0),
                totalTokens = metadata.totalTokenCount().orElse(0)
            )
        }

        // Extract grounding metadata
        val groundingMetadata = response.candidates().orElse(listOf()).firstOrNull()?.groundingMetadata()

        if (groundingMetadata == null) {
            logger.warn("No grounding metadata found in response")
            return GoogleTextSearchOutput(
                searchResult = SearchResult(
                    originalQuery = input.searchQuery,
                    answer = "",
                    content = "",
                    sources = emptyList()
                ),
                tokenUsage = tokenUsage
            )
        }

        // 1) Collect all grounding chunk URIs and resolve redirects in parallel
        val groundingChunks = groundingMetadata.orElseThrow().groundingChunks().orElse(listOf())
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
        val supports = groundingMetadata.orElseThrow().groundingSupports().orElse(listOf())

        // 3) Filter supports where at least one chunk URL exists
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
        }.distinct()

        val searchResult = SearchResult(
            originalQuery = input.searchQuery,
            answer = "",
            content = concatenatedText,
            sources = sources
        )

        logger.debug("Google text search results: '{}' from sources {}", concatenatedText, sources)

        return GoogleTextSearchOutput(
            searchResult = searchResult,
            tokenUsage = tokenUsage
        )
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


