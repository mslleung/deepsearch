package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ILinkRelevanceAnalysisAgent
import io.deepsearch.domain.agents.LinkRelevanceAnalysisInput
import io.deepsearch.domain.agents.LinkRelevanceAnalysisOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * An agent that analyzes HTML to identify links relevant to a query.
 * The agent extracts <a href> links from the HTML and ranks them by relevance.
 */
class LinkRelevanceAnalysisAgentAdkImpl : ILinkRelevanceAnalysisAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Serializable
    private data class RelevantLinkJson(
        val url: String,
        val reason: String
    )

    @Serializable
    private data class LinkAnalysisResponse(
        val links: List<RelevantLinkJson>
    )

    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("linkRelevanceAnalysisAgent")
        description("Agent to identify relevant links in HTML")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.2F)
                .responseMimeType("application/json")
                .thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(0)
                        .build()
                )
                .build()
        )
        instruction(
            """
                You are a link relevance analysis agent. Your task is to:
                1. Extract all <a href> links from the provided HTML
                2. Analyze which links are most relevant to the user's query
                3. Return the top relevant links with reasons why they are relevant
                
                Focus on links that would help answer the user's query or provide more detailed information.
                Ignore navigation links, headers, footers, and other irrelevant links.
                
                Return your response in JSON format:
                {
                  "links": [
                    {
                      "url": "https://example.com/page",
                      "reason": "This page contains information about..."
                    }
                  ]
                }
                
                If there are no relevant links, return an empty links array.
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    override suspend fun generate(input: LinkRelevanceAnalysisInput): LinkRelevanceAnalysisOutput {
        logger.debug("Analyzing link relevance for query: '{}'", input.query)

        val userPrompt = buildString {
            appendLine("Query: ${input.query}")
            appendLine()
            appendLine("HTML:")
            appendLine(input.html)
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

        val eventsFlow = runner.runAsync(
            session,
            Content.fromParts(Part.fromText(userPrompt)),
            RunConfig.builder().apply {
                setStreamingMode(RunConfig.StreamingMode.NONE)
                setMaxLlmCalls(1)
            }.build()
        ).asFlow()

        var responseText = ""
        eventsFlow.collect { event ->
            if (event.finalResponse()) {
                val contentOpt = event.content()
                if (contentOpt.isPresent) {
                    val content = contentOpt.get()
                    val partsOpt = content.parts()
                    if (partsOpt.isPresent && partsOpt.get().isNotEmpty()) {
                        val textOpt = partsOpt.get()[0].text()
                        if (textOpt.isPresent) {
                            responseText = textOpt.get()
                        }
                    }
                }
            }
        }

        val links = try {
            val response = json.decodeFromString<LinkAnalysisResponse>(responseText)
            response.links.map { linkJson ->
                WebpageLink(
                    url = linkJson.url,
                    source = LinkSource.LINK_RELEVANCE,
                    reason = linkJson.reason
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse link analysis response: {}", e.message)
            logger.debug("Response text: {}", responseText)
            emptyList()
        }

        logger.debug("Link relevance analysis found {} relevant links", links.size)

        return LinkRelevanceAnalysisOutput(links)
    }
}

