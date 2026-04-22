package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.AgenticTableConversionInput
import io.deepsearch.domain.agents.AgenticTableConversionOutput
import io.deepsearch.domain.agents.IAgenticTableConversionAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Multimodal table conversion agent that uses both a cropped screenshot and the
 * DOM-extracted HTML snippet to produce a clean HTML table.
 *
 * The image serves as visual ground truth for table structure, dimensions, and merged cells.
 * The HTML provides accurate cell text content, avoiding OCR errors.
 * When the HTML is empty or unhelpful (e.g. graphical/image-based tables), the agent
 * falls back to OCR from the screenshot.
 */
class AgenticTableConversionAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IAgenticTableConversionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Clean HTML table extracted from the region")
        .properties(
            mapOf(
                "htmlTable" to Schema.builder()
                    .type("STRING")
                    .description("The table expressed as clean HTML using <table>, <thead>, <tbody>, <tr>, <th>, <td> tags with colspan/rowspan for merged cells. Empty string if no table is found.")
                    .build()
            )
        )
        .required(listOf("htmlTable"))
        .build()

    private val systemInstruction = """
        You are a table extraction specialist. You are given:
        1. A screenshot/image of a table region from a webpage
        2. Optionally, the HTML snippet extracted from the DOM for that same region

        Your task is to produce a clean HTML <table> that accurately represents the table.

        HOW TO USE THE TWO INPUTS:
        - The IMAGE is the visual ground truth. Use it to determine:
          - The exact number of rows and columns
          - Which cells are merged (colspan/rowspan)
          - The overall table layout and structure
        - The HTML SNIPPET (when provided) contains the text content extracted from the DOM.
          Use it as the primary source for cell text, because it is more accurate than OCR.
          However, the HTML structure itself may be unreliable (CSS grid/flex layouts, nested divs, etc.).
        - If no HTML snippet is provided, or if it is clearly unhelpful (e.g., just an image tag
          or empty), extract all text from the image via OCR.

        HTML TABLE OUTPUT RULES:
        - Use <table>, <thead>, <tbody>, <tr>, <th>, <td> tags
        - Use <thead> for the header row(s) and <tbody> for data rows
        - For merged cells, use colspan and rowspan attributes (e.g., <td colspan="2">, <td rowspan="3">)
        - Make sure the table dimensions exactly match what is visible in the image
        - Do NOT invent data — only use text that appears in the HTML snippet or is visible in the image
        - Capture ALL text with no information loss
        - Use emojis (✅ ❌) for checkmarks/crosses instead of HTML entities
        - Output clean HTML without styling attributes (no class, style, etc.)
        - If the image does not contain a table, return an empty string

        Example output:
        <table>
            <thead>
                <tr>
                    <th>Feature</th>
                    <th>Free</th>
                    <th>Pro</th>
                </tr>
            </thead>
            <tbody>
                <tr>
                    <td>Users</td>
                    <td>1</td>
                    <td>Unlimited</td>
                </tr>
                <tr>
                    <td>Support</td>
                    <td colspan="2">Email only</td>
                </tr>
            </tbody>
        </table>

        Output JSON with 1 field:
        - "htmlTable": The clean HTML table. Empty string if no table found.
    """.trimIndent()

    @Serializable
    private data class TableConversionResponse(
        val htmlTable: String
    )

    override suspend fun generate(input: AgenticTableConversionInput): AgenticTableConversionOutput {
        val cleanedHtml = input.cleanedHtml?.let { html ->
            if (html.isNotBlank()) cleanHtml(html) else null
        }

        logger.debug(
            "Converting table region to HTML table (image {} bytes, html {} chars, cleaned {} chars)",
            input.regionImage.size,
            input.cleanedHtml?.length ?: 0,
            cleanedHtml?.length ?: 0
        )

        val userPromptText = buildString {
            appendLine("Context: ${input.auxiliaryInfo}")
            appendLine()
            if (!cleanedHtml.isNullOrBlank()) {
                appendLine("HTML snippet extracted from the DOM for this table region:")
                appendLine(cleanedHtml)
                appendLine()
                appendLine("Use the image as visual ground truth for structure and the HTML text for accurate cell content.")
            } else {
                appendLine("No HTML snippet is available for this table. Extract all content from the image using OCR.")
            }
        }

        val contentParts = listOf(
            Part.fromBytes(input.regionImage, input.imageMimeType.value),
            Part.fromText(userPromptText)
        )

        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<TableConversionResponse>(this@AgenticTableConversionAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(*(contentParts.toTypedArray()))),
                    GenerateContentConfig.builder()
                        .temperature(1.0F)
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                                .build()
                        )
                        .maxOutputTokens(8192)
                        .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                        .build()
                )

                result.checkFinishReason()

                result.usageMetadata().ifPresent { metadata ->
                    tokenUsage = TokenUsageMetrics(
                        modelName = modelId,
                        promptTokens = metadata.promptTokenCount().orElse(0),
                        outputTokens = metadata.candidatesTokenCount().orElse(0),
                        totalTokens = metadata.totalTokenCount().orElse(0)
                    )
                }

                result.text() ?: throw RuntimeException("No text response from model")
            }
        }

        logger.debug(
            "Table conversion complete: {} chars HTML table output (tokens: {})",
            response.htmlTable.length, tokenUsage.totalTokens
        )

        return AgenticTableConversionOutput(
            htmlTable = response.htmlTable,
            tokenUsage = tokenUsage
        )
    }

    // ========== HTML Cleaning ==========

    private fun cleanHtml(rawHtml: String): String {
        val doc = Jsoup.parseBodyFragment(rawHtml)
        doc.outputSettings().prettyPrint(false)

        doc.select("image").forEach { element ->
            element.unwrap()
        }

        doc.select("svg").forEach { element ->
            val altText = element.attr("aria-label").ifBlank {
                element.selectFirst("title")?.text() ?: "[icon]"
            }
            element.replaceWith(TextNode(altText))
        }
        doc.select("img").forEach { element ->
            val altText = element.attr("alt").ifBlank { "[image]" }
            element.replaceWith(TextNode(altText))
        }

        doc.select(
            "script, style, noscript, template, canvas, meta, link, iframe, object, embed, " +
                    "head, title, base, " +
                    "video, audio, source, track, picture, " +
                    "form, input, select, textarea, label, fieldset, legend, " +
                    "nav, footer, header, aside"
        ).remove()

        doc.select("*").forEach { element ->
            val nodesToRemove = element.childNodes().filter { node ->
                node.nodeName() == "#comment" || node.nodeName() == "#pi"
            }
            nodesToRemove.forEach { node -> node.remove() }
        }

        doc.select("*").forEach { element ->
            val attributes = element.attributes().asList()
            val attrsToKeep = attributes.filter { attr ->
                when (attr.key) {
                    "colspan", "rowspan", "scope" -> true
                    "id" -> true
                    "role" -> attr.value in listOf("table", "row", "cell", "rowgroup", "columnheader", "rowheader", "gridcell")
                    else -> false
                }
            }
            element.clearAttributes()
            attrsToKeep.forEach { attr ->
                element.attr(attr.key, attr.value)
            }
        }

        unwrapRedundantWrappers(doc)

        val preserveTags = setOf("td", "th", "tr", "table", "thead", "tbody", "tfoot", "caption")
        val bodyChildren = doc.body().children().toList()
        for (child in bodyChildren) {
            removeEmptyElementsBottomUp(child, preserveTags)
        }

        normalizeWhitespace(doc)

        return doc.body().html()
    }

    private fun unwrapRedundantWrappers(doc: org.jsoup.nodes.Document) {
        val wrapperTags = setOf("div", "span")
        repeat(3) {
            val toUnwrap = doc.body().select("*").filter { element ->
                element.tagName() in wrapperTags &&
                        element.attributes().isEmpty &&
                        element.childrenSize() == 1 &&
                        element.ownText().isBlank()
            }
            toUnwrap.forEach { element -> element.unwrap() }
            if (toUnwrap.isEmpty()) return@repeat
        }
    }

    private fun normalizeWhitespace(doc: org.jsoup.nodes.Document) {
        doc.body().traverse(object : org.jsoup.select.NodeVisitor {
            override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                if (node is TextNode) {
                    val original = node.wholeText
                    val normalized = original.replace(Regex("\\s+"), " ")
                    if (normalized != original) {
                        node.text(normalized)
                    }
                }
            }
            override fun tail(node: org.jsoup.nodes.Node, depth: Int) {}
        })
    }

    private fun removeEmptyElementsBottomUp(
        element: org.jsoup.nodes.Element,
        preserveTags: Set<String>,
        depth: Int = 0
    ): Int {
        if (depth > 1000) return 0

        var removedCount = 0
        val children = element.children().toList()
        for (child in children) {
            removedCount += removeEmptyElementsBottomUp(child, preserveTags, depth + 1)
        }

        val isEmpty = element.children().isEmpty() && element.ownText().isBlank()
        val shouldPreserve = preserveTags.contains(element.tagName().lowercase())
        if (isEmpty && !shouldPreserve) {
            element.remove()
            removedCount++
        }

        return removedCount
    }
}
