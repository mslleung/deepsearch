package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
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
     * Unlike other agents, this one preserves actual content text since markdown needs it.
     */
    private fun cleanHtml(rawHtml: String): String {
        val doc: Document = Jsoup.parse(rawHtml)

        // Step 1: Remove obvious non-content elements (scripts, styles, etc.)
        doc.select(
            "script, style, noscript, template, svg, canvas, meta, link, iframe, object, embed, " +
            "head, title, base"
        ).remove()

        // Step 2: Remove navigation elements (these don't belong in main content)
        doc.select(
            "nav, header, footer, aside, " +
            "[role=navigation], [role=banner], [role=contentinfo], [role=complementary]"
        ).remove()

        // Step 3: Remove form elements (not part of readable content)
        doc.select("form, input, button, select, textarea").remove()

        // Step 4: Remove comments and processing instructions
        doc.select("*").forEach { element ->
            val nodesToRemove = element.childNodes().filter { node ->
                node.nodeName() == "#comment" || node.nodeName() == "#pi"
            }
            nodesToRemove.forEach { node -> node.remove() }
        }

        // Step 5: Convert media elements to text placeholders (preserve alt text)
        doc.select("img, video, audio").forEach { media ->
            val alt = media.attr("alt").take(100)
            val title = media.attr("title").take(100)
            val src = media.attr("src").take(50)
            
            val description = buildString {
                if (alt.isNotEmpty()) append(alt)
                else if (title.isNotEmpty()) append(title)
                else if (src.isNotEmpty()) append("[media: $src]")
            }
            
            if (description.isNotEmpty()) {
                media.text("[$description]")
                media.tagName("span") // Replace with span to preserve text flow
            } else {
                media.remove()
            }
        }

        // Step 6: Keep only content-relevant attributes (for links, etc.)
        doc.select("*").forEach { element ->
            val essentialAttrs = setOf("href", "src", "alt", "title", "colspan", "rowspan")
            val attrsToKeep = element.attributes().filter { attr ->
                attr.key in essentialAttrs
            }
            element.clearAttributes()
            attrsToKeep.forEach { attr ->
                element.attr(attr.key, attr.value)
            }
        }

        // Step 7: Normalize whitespace in text nodes (but don't truncate - we need full content!)
        doc.select("*").forEach { element ->
            element.textNodes().forEach { textNode ->
                val normalized = textNode.text().replace("\\s+".toRegex(), " ").trim()
                textNode.text(normalized)
            }
        }

        // Step 8: Remove empty elements that don't contribute to content
        var changed = true
        while (changed) {
            changed = false
            val emptyElements = doc.select("*").filter { element ->
                element.children().isEmpty() &&
                element.ownText().isBlank() &&
                element.tagName() !in setOf("br", "hr") // Keep structural breaks
            }
            if (emptyElements.isNotEmpty()) {
                emptyElements.forEach { it.remove() }
                changed = true
            }
        }

        val cleanedHtml = doc.outerHtml()
        logger.debug("Cleaned HTML character count: {} (original: ~{})", cleanedHtml.length, rawHtml.length)
        return cleanedHtml
    }
}
