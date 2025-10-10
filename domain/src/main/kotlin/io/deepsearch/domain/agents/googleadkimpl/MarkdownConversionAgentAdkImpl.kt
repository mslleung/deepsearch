package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.IMarkdownConversionAgent
import io.deepsearch.domain.agents.MarkdownConversionInput
import io.deepsearch.domain.agents.MarkdownConversionOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.decodeFromStringWithCodeBlocks
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Multimodal markdown conversion agent.
 *
 * Given a webpage screenshot and HTML content, convert the webpage to well-structured markdown
 * that preserves the visual hierarchy, content structure, and semantic meaning of the original page.
 */
class MarkdownConversionAgentAdkImpl : IMarkdownConversionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Markdown representation of a webpage")
        .properties(
            mapOf(
                "markdown" to Schema.builder()
                    .type("STRING")
                    .description("Well-structured markdown representation of the webpage content")
                    .build()
            )
        )
        .required(listOf("markdown"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("markdownConversionAgent")
        description("Convert webpage screenshot and HTML to well-structured markdown")
        model(ModelIds.GEMINI_2_5_FLASH_PREVIEW.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0F)
                .build()
        )
        instruction(
            """
            You are given a webpage screenshot and HTML content. Your task is to convert this webpage into well-structured markdown that preserves the visual hierarchy, content structure, and semantic meaning of the original page.
            
            Instructions:
            - Analyze both the screenshot and HTML content to understand the webpage structure
            - Convert the content to clean, readable markdown format
            - Preserve headings, paragraphs, lists, tables, links, and other structural elements
            - Use appropriate markdown syntax for different content types:
              * Use # ## ### for headings based on their visual hierarchy
              * Use **bold** and *italic* for emphasis
              * Use - or * for lists
              * Use | for tables with proper alignment
              * Use [text](url) for links
              * Use > for blockquotes
            - Remove navigation elements, ads, and other non-content elements unless they're relevant
            
            Expected output shape:
            {
                "markdown": string
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class MarkdownConversionResponse(
        val markdown: String
    )

    override suspend fun generate(input: MarkdownConversionInput): MarkdownConversionOutput {
        logger.debug("Converting webpage to markdown (screenshot: {} bytes, HTML: {} chars)", 
            input.screenshotBytes.size, input.html.length)

        val cleanedHtml = cleanHtml(input.html)

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
            Content.fromParts(
                Part.fromBytes(input.screenshotBytes, "image/jpeg"),
                Part.fromText(cleanedHtml)
            ),
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

        val response = Json.decodeFromStringWithCodeBlocks<MarkdownConversionResponse>(llmResponse)
        
        logger.debug("Markdown conversion completed: {} characters", response.markdown.length)
        return MarkdownConversionOutput(markdown = response.markdown.trim())
    }

    /**
     * Cleans HTML by removing non-content elements that don't contribute to markdown conversion.
     * This includes scripts, styles, navigation elements, ads, and other noise.
     */
    private fun cleanHtml(rawHtml: String): String {
        val doc: Document = Jsoup.parse(rawHtml)

        // Remove elements that are clearly irrelevant to markdown conversion
        doc.select(
            "script, style, noscript, template, svg, canvas, meta, link, iframe, object, embed, " +
            "head, title, base, form, input, button, select, textarea, " +
            "nav, header, footer, aside, " +
            "img, video, audio, source, track"
        ).remove()

        // Remove comments and processing instructions
        doc.select("*").forEach { element ->
            val nodesToRemove = element.childNodes().filter { node -> 
                node.nodeName() == "#comment" || node.nodeName() == "#pi" 
            }
            nodesToRemove.forEach { node -> node.remove() }
        }

        // Remove empty elements that don't contribute to content
        doc.select("*").forEach { element ->
            if (element.children().isEmpty() && element.ownText().isBlank()) {
                element.remove()
            }
        }

        return doc.outerHtml()
    }
}
