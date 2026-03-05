package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.ILinearizedContentConversionAgent
import io.deepsearch.domain.agents.LinearizedContentConversionInput
import io.deepsearch.domain.agents.LinearizedContentConversionOutput
import io.deepsearch.domain.agents.SnippetClassification
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Converts hidden container HTML (CSS/div-based table-like content) to linearized
 * structured text instead of markdown tables. Used when spatial analysis has
 * identified a table candidate inside accordions, tabs, or collapsed sections.
 *
 * Output format: each row's values are explicitly labeled with column headers
 * (e.g. "Basic Plan: - Price: $10/mo - Users: 5") for better downstream
 * retrieval and LLM understanding.
 */
class LinearizedContentConversionAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : ILinearizedContentConversionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Classification and linearized structured text representation")
        .properties(
            mapOf(
                "classification" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("TABLE", "CARD", "LIST", "COOKIE_DECLARATION_TABLE", "HIDDEN_MOBILE_LAYOUT"))
                    .description("Content type: TABLE/CARD (grid-like), LIST (bullets), COOKIE_DECLARATION_TABLE, HIDDEN_MOBILE_LAYOUT (removable)")
                    .build(),
                "structuredText" to Schema.builder()
                    .type("STRING")
                    .description("Content as linearized text: each row with explicit header labels (e.g. 'Plan: - Price: X - Users: Y'). No markdown table syntax. Empty for removable classifications.")
                    .build()
            )
        )
        .required(listOf("classification", "structuredText"))
        .build()

    private val systemInstruction = """
        You are given an HTML snippet from a hidden container (accordion, tab, collapsed section) that has been detected as having a grid-like structure through spatial analysis.
        Your task is to classify it and convert it to LINEARIZED STRUCTURED TEXT (not a markdown table).

        Content classification:
        - TABLE: DEFAULT. Rows and columns (pricing, comparison, feature matrices, specs). CSS-based div grids are TABLES.
        - CARD: Card-like repeated items (only if NOT grid-aligned).
        - LIST: Bullet or numbered lists (only if single column).
        - COOKIE_DECLARATION_TABLE: Cookie consent/declaration tables (legal boilerplate). Set structuredText to empty string.
        - HIDDEN_MOBILE_LAYOUT: Hidden mobile-specific duplicate content. Set structuredText to empty string.

        LINEARIZED OUTPUT RULES (for TABLE and CARD):
        - Do NOT use markdown table syntax: no pipe characters (|), no --- separators.
        - Output each content row EXACTLY ONCE. Do NOT repeat the same data in multiple formats. Do NOT add section headers, summaries, or alternative layouts.
        - Represent each logical row as a block: start with the row label, then list each column value with its header label.
        - COLUMN HEADERS (priority order):
          1. If the HTML itself contains column or row headers, use them.
          2. If the Auxiliary Info contains "Visible content above", look for short plan/tier/column names (e.g., "Free", "Pro AI", "Premium AI", "Enterprise AI"). Ignore any pricing text, descriptions, URLs, or other noise. Use only the short names as column labels, matching left-to-right.
          3. If NEITHER source provides clearly identifiable column headers, list values in order separated by commas. Do NOT fabricate or guess labels like "Column 1", "Plan 1", etc. It is better to have no labels than wrong labels.
        - CRITICAL: When column headers ARE clearly available, you MUST label EVERY column value with its header — including checkmarks, ticks, crosses, and icons. Never leave values unlabeled when headers are known.
        - Icon handling: Convert "{tick icon}", "{cross icon}", "[icon]", or similar icon markers to ✅ or ❌ based on context (tick/check = ✅, cross/x = ❌). If an icon's meaning is unclear, use ✅ for positive/present markers and ❌ for negative/absent markers within comparison tables.
        - Example output with column headers from visible content above:
          Shopify:
          - Free: ✅
          - Pro AI: ✅
          - Premium AI: ✅
          - Enterprise AI: ✅

          Facebook Lead Ads:
          - Free: ❌
          - Pro AI: ✅
          - Premium AI: ✅
          - Enterprise AI: ✅
        - Example output with headers from the HTML itself:
          Basic Plan:
          - Price: $10/month
          - Users: Up to 5
          - Storage: 10 GB

          Pro Plan:
          - Price: $30/month
          - Users: Unlimited
          - Storage: 100 GB
        - Preserve ALL text content with no information loss.
        - Use emojis (✅ ❌) for checkmarks/crosses instead of HTML entities.
        - For LIST classification, output as bullet points or numbered list as appropriate.

        Output JSON with 2 fields:
        - "classification": one of "TABLE", "CARD", "LIST", "COOKIE_DECLARATION_TABLE", "HIDDEN_MOBILE_LAYOUT"
        - "structuredText": the linearized text. Empty string for COOKIE_DECLARATION_TABLE and HIDDEN_MOBILE_LAYOUT.
    """.trimIndent()

    @Serializable
    private data class LinearizedResponse(
        val classification: String,
        val structuredText: String
    )

    override suspend fun generate(input: LinearizedContentConversionInput): LinearizedContentConversionOutput {
        val cleanedHtml = cleanHtml(input.html)
        logger.debug("Linearized conversion: html length {} -> cleaned {}", input.html.length, cleanedHtml.length)

        val userPrompt = buildString {
            if (input.auxiliaryInfo.isNotBlank()) appendLine("Auxiliary Info: ${input.auxiliaryInfo}")
            appendLine(cleanedHtml)
            appendLine("Please generate the response in JSON structured output")
        }

        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<LinearizedResponse>(this@LinearizedContentConversionAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    userPrompt,
                    GenerateContentConfig.builder()
                        .temperature(1.0F)
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.MINIMAL).build())
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

        val classification = SnippetClassification.fromString(response.classification)
        val structuredText = response.structuredText.trim()
        logger.debug("Linearized conversion complete: classification={}, {} chars", classification, structuredText.length)
        return LinearizedContentConversionOutput(
            classification = classification,
            structuredText = structuredText,
            tokenUsage = tokenUsage
        )
    }

    override suspend fun generateBatch(inputs: List<LinearizedContentConversionInput>): List<LinearizedContentConversionOutput> = coroutineScope {
        if (inputs.isEmpty()) return@coroutineScope emptyList()
        inputs.map { input -> async { generate(input) } }.awaitAll()
    }

    private fun cleanHtml(rawHtml: String): String {
        val doc = Jsoup.parseBodyFragment(rawHtml)
        doc.outputSettings().prettyPrint(false)
        doc.select("image").forEach { it.unwrap() }
        doc.select("svg").forEach { element ->
            val altText = element.attr("aria-label").ifBlank { element.selectFirst("title")?.text() ?: "[icon]" }
            element.replaceWith(TextNode(altText))
        }
        doc.select("img").forEach { element ->
            val altText = element.attr("alt").ifBlank { "[image]" }
            element.replaceWith(TextNode(altText))
        }
        doc.select(
            "script, style, noscript, template, canvas, meta, link, iframe, object, embed, " +
                "head, title, base, video, audio, source, track, picture, " +
                "form, input, select, textarea, label, fieldset, legend, nav, footer, header, aside"
        ).remove()
        doc.select("*").forEach { element ->
            element.childNodes().filter { it.nodeName() == "#comment" || it.nodeName() == "#pi" }.forEach { it.remove() }
        }
        doc.select("*").forEach { element ->
            val attrsToKeep = element.attributes().asList().filter { attr ->
                when (attr.key) {
                    "colspan", "rowspan", "scope", "id" -> true
                    "role" -> attr.value in listOf("table", "row", "cell", "rowgroup", "columnheader", "rowheader", "gridcell")
                    else -> false
                }
            }
            element.clearAttributes()
            attrsToKeep.forEach { element.attr(it.key, it.value) }
        }
        unwrapRedundantWrappers(doc)
        val preserveTags = setOf("td", "th", "tr", "table", "thead", "tbody", "tfoot", "caption")
        doc.body().children().toList().forEach { removeEmptyElementsBottomUp(it, preserveTags) }
        normalizeWhitespace(doc)
        return doc.body().html()
    }

    private fun unwrapRedundantWrappers(doc: org.jsoup.nodes.Document) {
        val wrapperTags = setOf("div", "span")
        repeat(3) {
            doc.body().select("*").filter { element ->
                element.tagName() in wrapperTags &&
                    element.attributes().isEmpty &&
                    element.childrenSize() == 1 &&
                    element.ownText().isBlank()
            }.forEach { it.unwrap() }
        }
    }

    private fun normalizeWhitespace(doc: org.jsoup.nodes.Document) {
        doc.body().traverse(object : org.jsoup.select.NodeVisitor {
            override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                if (node is TextNode) {
                    val normalized = node.wholeText.replace(Regex("\\s+"), " ")
                    if (normalized != node.wholeText) node.text(normalized)
                }
            }
            override fun tail(node: org.jsoup.nodes.Node, depth: Int) {}
        })
    }

    private fun removeEmptyElementsBottomUp(element: org.jsoup.nodes.Element, preserveTags: Set<String>, depth: Int = 0): Int {
        if (depth > 1000) return 0
        var removed = 0
        element.children().toList().forEach { child -> removed += removeEmptyElementsBottomUp(child, preserveTags, depth + 1) }
        if (element.children().isEmpty() && element.ownText().isBlank() && element.tagName().lowercase() !in preserveTags) {
            element.remove()
            removed++
        }
        return removed
    }
}
