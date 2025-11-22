package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IMarkdownConversionAgent
import io.deepsearch.domain.agents.MarkdownConversionInput
import io.deepsearch.domain.agents.MarkdownConversionOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Multimodal markdown conversion agent.
 *
 * Given a webpage screenshot and HTML content, convert the webpage to well-structured markdown
 * that preserves the visual hierarchy, content structure, and semantic meaning of the original page.
 */
class MarkdownConversionAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IMarkdownConversionAgent {

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

    private val systemInstruction = """
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

    @Serializable
    private data class MarkdownConversionResponse(
        val markdown: String
    )

    override suspend fun generate(input: MarkdownConversionInput): MarkdownConversionOutput {
        logger.debug("Converting webpage to markdown (screenshot: {} bytes, HTML: {} chars)", 
            input.screenshotBytes.size, input.html.length)

        val cleanedHtml = cleanHtml(input.html)

        val response = retryLlmCall<MarkdownConversionResponse> {
            val result = client.models.generateContent(
                ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
                listOf(
                    Content.fromParts(
                        Part.fromBytes(input.screenshotBytes, "image/jpeg"),
                        Part.fromText(cleanedHtml)
                    )
                ),
                GenerateContentConfig.builder()
                    .temperature(0F)
                    .responseSchema(outputSchema)
                    .thinkingConfig(
                        ThinkingConfig.builder()
                            .thinkingBudget(0)
                            .build()
                    )
                    .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                    .build()
            )

            result.checkFinishReason()

            result.text() ?: throw RuntimeException("No text response from model")
        }
        
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


