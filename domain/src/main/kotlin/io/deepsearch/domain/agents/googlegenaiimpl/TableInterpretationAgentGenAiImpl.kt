package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.SnippetClassification
import io.deepsearch.domain.agents.TableInterpretationBatchResult
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.agents.TableInterpretationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.TableMarkdownUtils
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TableInterpretationAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : ITableInterpretationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Classification and HTML table representation of an HTML snippet")
        .properties(
            mapOf(
                "classification" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("TABLE", "CARD", "LIST", "COOKIE_DECLARATION_TABLE", "HIDDEN_MOBILE_LAYOUT"))
                    .description("Content type classification: TABLE (pricing, comparison, feature tables with rows/columns), CARD (card-like structures), LIST (bullet points), COOKIE_DECLARATION_TABLE (cookie consent tables), HIDDEN_MOBILE_LAYOUT (hidden mobile-specific content)")
                    .build(),
                "additionalInfo" to Schema.builder()
                    .type("STRING")
                    .description("Optional additional context, notes, or clarifications that cannot be represented in the table structure (e.g., badges, labels, footnotes, ambiguities).")
                    .build(),
                "htmlTable" to Schema.builder()
                    .type("STRING")
                    .description("The content expressed as an HTML table using <table>, <tr>, <th>, <td> tags with colspan/rowspan for merged cells. For COOKIE_DECLARATION_TABLE and HIDDEN_MOBILE_LAYOUT, leave empty.")
                    .build(),
            )
        )
        .required(listOf("classification", "additionalInfo", "htmlTable"))
        .build()

    private val systemInstruction = """
        You are given a HTML snippet that has been detected as having a grid-like structure through spatial analysis.
        Your task is to convert it to a clean HTML TABLE with proper structure.

        IMPORTANT: This snippet has already been verified to have tabular structure (rows × columns) through bounding box analysis.
        Default to TABLE classification unless it clearly matches one of the special categories below.

        Content classification:
        - TABLE: DEFAULT. Any content with rows and columns (pricing tables, comparison tables, feature matrices, specification tables). CSS-based div layouts that form grids are TABLES.
        - CARD: Card-like structures with repeated similar items (only if NOT grid-aligned)
        - LIST: Bullet points or numbered lists (only if single column)
        - COOKIE_DECLARATION_TABLE: Cookie consent/declaration tables (legal boilerplate listing cookies). Set htmlTable to empty string.
        - HIDDEN_MOBILE_LAYOUT: Hidden mobile-specific layouts that duplicate desktop content. Set htmlTable to empty string.

        Special handling for removable content:
        - COOKIE_DECLARATION_TABLE and HIDDEN_MOBILE_LAYOUT will be removed from the document
        - For these classifications, set htmlTable to empty string ""
        - Set additionalInfo to briefly describe what was detected

        HTML table conversion rules (for TABLE):
        - Convert the content to an HTML table using <table>, <thead>, <tbody>, <tr>, <th>, <td> tags
        - Use <thead> for the header row and <tbody> for data rows
        - For merged cells, use colspan and rowspan attributes (e.g., <td colspan="2">, <td rowspan="3">)
        - Make sure the table dimensions correctly reflect the grid structure
        - Preserve row/column structure from the spatial analysis
        - Do not invent data - only use what's in the HTML
        - Capture ALL text with no information loss
        - Use emojis (✅ ❌) for checkmarks/crosses instead of HTML entities
        - Output clean HTML without styling attributes (no class, style, etc.)
        
        Example output for a pricing table:
        <table>
            <thead>
                <tr>
                    <th>Feature</th>
                    <th>Free</th>
                    <th>Pro</th>
                    <th>Enterprise</th>
                </tr>
            </thead>
            <tbody>
                <tr>
                    <td>Users</td>
                    <td>1</td>
                    <td>10</td>
                    <td>Unlimited</td>
                </tr>
                <tr>
                    <td>Storage</td>
                    <td>1GB</td>
                    <td>10GB</td>
                    <td>Unlimited</td>
                </tr>
                <tr>
                    <td>Support</td>
                    <td colspan="2">Email only</td>
                    <td>24/7 Priority</td>
                </tr>
            </tbody>
        </table>

        Output JSON with 3 fields:
        - "classification": string - one of "TABLE", "CARD", "LIST", "COOKIE_DECLARATION_TABLE", "HIDDEN_MOBILE_LAYOUT"
        - "additionalInfo": ONLY for critical clarifications that CANNOT fit in the table. Empty string if not needed.
        - "htmlTable": The content expressed as a clean HTML table. Empty for COOKIE_DECLARATION_TABLE and HIDDEN_MOBILE_LAYOUT.

        Output structure:
        {
          "classification": string,
          "additionalInfo": string,
          "htmlTable": string
        }
    """.trimIndent()

    @Serializable
    private data class TableInterpretationResponse(
        val classification: String,
        val additionalInfo: String,
        val htmlTable: String,
    )

    override suspend fun generate(input: TableInterpretationInput): TableInterpretationOutput {
        // Use pre-computed HTML (bounding boxes are available but not injected by default)
        val tableHtml = input.tableHtml

        logger.debug("Interpreting table to markdown (html length {})", tableHtml.length)

        // Clean HTML to reduce token usage and noise
        // Note: Bounding boxes are not injected by default as LLMs understand HTML structure well
        // without spatial information for typical tables. This significantly reduces token count.
        val cleanedHtml = cleanHtml(tableHtml)

        logger.debug("Cleaned HTML length: {} (original: {}, reduction: {}%)", 
            cleanedHtml.length, tableHtml.length, 
            ((tableHtml.length - cleanedHtml.length) * 100 / tableHtml.length.coerceAtLeast(1)))

        val userPrompt = buildString {
            appendLine("Auxiliary Info: ${input.tableIdentification.auxiliaryInfo}")
            appendLine(cleanedHtml)
            appendLine("Please generate the response in JSON structured output")
        }

        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<TableInterpretationResponse>(this@TableInterpretationAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    userPrompt,
                    GenerateContentConfig.builder()
                        .temperature(1.0F)
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                        .build()
                )

                result.checkFinishReason()
                
                // Extract token usage
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
        
        // Convert HTML table to markdown programmatically (handles rowspan/colspan properly)
        val markdown = if (response.htmlTable.isNotBlank()) {
            transformHTMLTablesToMarkdown(response.htmlTable)
        } else {
            ""
        }
        
        val combinedMarkdown = combineMarkdownWithAdditionalInfo(classification, markdown, response.additionalInfo)
        
        logger.debug("Table interpretation complete: classification={}, {} chars htmlTable -> {} chars markdown, {} chars additionalInfo", 
            classification, response.htmlTable.length, markdown.length, response.additionalInfo.length
        )
        return TableInterpretationOutput(
            classification = classification,
            markdown = combinedMarkdown,
            tokenUsage = tokenUsage
        )
    }

    /**
     * Inject bounding box attributes into HTML elements that have data-ds-id.
     * Bounding boxes are keyed by data-ds-id (not XPath).
     * 
     * NOTE: This function is intentionally not used by default.
     * Testing showed that LLMs understand HTML table structure well without spatial information,
     * and injecting bounding boxes increases token count significantly (can add 5-10% to HTML size).
     * This function is retained for potential future use cases where spatial layout is ambiguous.
     */
    @Suppress("unused")
    private fun injectBoundingBoxes(html: String, boundingBoxes: Map<String, IBrowserPage.BoundingBox>): String {
        if (boundingBoxes.isEmpty()) {
            return html
        }

        try {
            // Parse the HTML fragment (table element)
            val doc = Jsoup.parseBodyFragment(html)
            doc.outputSettings().prettyPrint(false)

            // Tags relevant for table interpretation (structure and cell boundaries)
            // Includes semantic table elements and divs for CSS-based tables
            val relevantTags = setOf("table", "thead", "tbody", "tfoot", "tr", "td", "th", "div", "li", "ul", "ol", "dl", "dt", "dd")

            // Find all elements with data-ds-id and inject bounding boxes
            for ((dsId, bbox) in boundingBoxes) {
                val width = bbox.right - bbox.left
                val height = bbox.bottom - bbox.top

                // If the element truly has 0 0 0 0 bounding box (or 0 size), do not inject as it has no meaning
                if (width <= 0.0 && height <= 0.0) {
                    continue
                }

                val bboxValue = "${bbox.left.toInt()} ${bbox.top.toInt()} ${bbox.right.toInt()} ${bbox.bottom.toInt()}"

                // Find element by data-ds-id
                val element = doc.selectFirst("[data-ds-id=\"$dsId\"]")

                // Only inject bounding boxes on elements relevant for table structure
                if (element != null && element.tagName() in relevantTags) {
                    element.attr("ds-bounding-box", bboxValue)
                }
            }

            // Return only the body's inner HTML (the table element)
            return doc.body().html()
        } catch (e: Exception) {
            logger.warn("Failed to inject bounding boxes: {}", e.message)
            return html
        }
    }


    /**
     * Combines markdown with additionalInfo based on classification type.
     * 
     * Rules:
     * - TABLE/CARD: Append additionalInfo on new line
     * - LIST: Drop additionalInfo, return only markdown
     * - COOKIE_DECLARATION_TABLE/HIDDEN_MOBILE_LAYOUT: Return markdown + additionalInfo (will be removed later)
     */
    private fun combineMarkdownWithAdditionalInfo(
        classification: SnippetClassification,
        markdown: String,
        additionalInfo: String?
    ): String {
        if (additionalInfo.isNullOrBlank()) {
            return markdown
        }
        
        val trimmedInfo = additionalInfo.trim()
        
        return when (classification) {
            SnippetClassification.TABLE, SnippetClassification.CARD -> {
                // Append additionalInfo on new line
                "$markdown\n$trimmedInfo"
            }
            SnippetClassification.LIST -> {
                // Drop additionalInfo, just use markdown
                markdown
            }
            SnippetClassification.COOKIE_DECLARATION_TABLE, SnippetClassification.HIDDEN_MOBILE_LAYOUT -> {
                // Return markdown + additionalInfo (will be removed later, doesn't matter)
                "$markdown\n\n*$trimmedInfo*"
            }
        }
    }

    private fun cleanHtml(rawHtml: String): String {
        val doc = Jsoup.parseBodyFragment(rawHtml)
        doc.outputSettings().prettyPrint(false)

        // Step 0: Strip image XML tags but keep their text content
        // Image tags are in format: <image id="img-xxx">text</image>
        // We want to preserve the interpreted text for table understanding
        doc.select("image").forEach { element ->
            element.unwrap()
        }

        // Step 1a: Replace unreplaced svg/img with placeholder text
        // If icon/image replacement worked, these elements wouldn't exist anymore
        // (they'd be <span> or <image> tags). If they're still svg/img, the
        // replacement failed and we should keep a placeholder for the LLM.
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

        // Step 1b: Remove noise elements (no longer includes svg/img - handled above)
        doc.select(
            "script, style, noscript, template, canvas, meta, link, iframe, object, embed, " +
                    "head, title, base, " +
                    "video, audio, source, track, picture, " +
                    "form, input, select, textarea, label, fieldset, legend, " +
                    "nav, footer, header, aside"
        ).remove()

        // Step 2: Remove comments and processing instructions
        doc.select("*").forEach { element ->
            val nodesToRemove = element.childNodes().filter { node ->
                node.nodeName() == "#comment" || node.nodeName() == "#pi"
            }
            nodesToRemove.forEach { node -> node.remove() }
        }

        // Step 3: Strip attributes aggressively to reduce token count
        // Only keep attributes that are semantically meaningful for table interpretation
        doc.select("*").forEach { element ->
            val attributes = element.attributes().asList()
            val attrsToKeep = attributes.filter { attr ->
                when (attr.key) {
                    // Table-specific attributes essential for structure
                    "colspan", "rowspan", "scope" -> true
                    // Keep id for element identification
                    "id" -> true
                    // Semantic role for accessibility-based tables
                    "role" -> attr.value in listOf("table", "row", "cell", "rowgroup", "columnheader", "rowheader", "gridcell")
                    // Skip class, data-* attributes to reduce token count
                    // Class names (especially Tailwind/utility classes) can be very verbose
                    // data-* attrs are framework-specific and add no semantic value
                    else -> false
                }
            }
            element.clearAttributes()
            attrsToKeep.forEach { attr ->
                element.attr(attr.key, attr.value)
            }
        }

        // Step 4: Unwrap non-semantic wrapper elements
        // Elements like <div>, <span> that only wrap a single child and have no attributes
        // can be unwrapped to simplify the DOM structure
        unwrapRedundantWrappers(doc)

        // Step 5: Remove empty elements, preserving table structure
        val preserveTags = setOf("td", "th", "tr", "table", "thead", "tbody", "tfoot", "caption")

        // Use bottom-up (post-order) traversal to remove empty elements in a single pass
        // This processes children before parents, so nested empty elements are handled correctly
        // Process children of body, not body itself (body should never be removed)
        var removedCount = 0
        val bodyChildren = doc.body().children().toList()
        for (child in bodyChildren) {
            removedCount += removeEmptyElementsBottomUp(child, preserveTags)
        }
        logger.trace("Removed {} empty elements in single pass", removedCount)

        // Step 6: Normalize whitespace in text nodes to reduce token count
        normalizeWhitespace(doc)

        return doc.body().html()
    }

    /**
     * Unwrap non-semantic wrapper elements that add no value.
     * A wrapper is considered redundant if:
     * - It's a generic container (div, span)
     * - It has no attributes (after stripping)
     * - It contains only a single element child (no text siblings)
     */
    private fun unwrapRedundantWrappers(doc: org.jsoup.nodes.Document) {
        val wrapperTags = setOf("div", "span")
        var unwrappedCount = 0
        
        // Multiple passes since unwrapping can create new redundant wrappers
        repeat(3) {
            val toUnwrap = doc.body().select("*").filter { element ->
                element.tagName() in wrapperTags &&
                element.attributes().isEmpty &&
                element.childrenSize() == 1 &&
                element.ownText().isBlank()
            }
            
            toUnwrap.forEach { element ->
                element.unwrap()
                unwrappedCount++
            }
            
            if (toUnwrap.isEmpty()) return@repeat
        }
        
        if (unwrappedCount > 0) {
            logger.trace("Unwrapped {} redundant wrapper elements", unwrappedCount)
        }
    }

    /**
     * Normalize whitespace in text nodes to reduce token count.
     * Collapses multiple spaces/newlines into single spaces.
     */
    private fun normalizeWhitespace(doc: org.jsoup.nodes.Document) {
        doc.body().traverse(object : org.jsoup.select.NodeVisitor {
            override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                if (node is TextNode) {
                    val original = node.wholeText
                    // Collapse multiple whitespace characters into single space
                    val normalized = original.replace(Regex("\\s+"), " ")
                    if (normalized != original) {
                        node.text(normalized)
                    }
                }
            }
            override fun tail(node: org.jsoup.nodes.Node, depth: Int) {}
        })
    }

    /**
     * Removes empty elements using a bottom-up (post-order) traversal.
     * This processes children before parents, allowing nested empty elements to be removed in a single pass.
     *
     * @param element The root element to process
     * @param preserveTags Set of tag names that should never be removed, even if empty
     * @param depth Current recursion depth (for safety checking)
     * @return The count of removed elements
     */
    private fun removeEmptyElementsBottomUp(
        element: org.jsoup.nodes.Element,
        preserveTags: Set<String>,
        depth: Int = 0
    ): Int {
        // Safety check to prevent stack overflow (DOM trees shouldn't be this deep)
        if (depth > 1000) {
            logger.error("removeEmptyElementsBottomUp exceeded max depth of 1000, stopping traversal")
            return 0
        }

        var removedCount = 0

        // Process all children first (post-order traversal)
        // Use toList() to create a snapshot and avoid ConcurrentModificationException
        val children = element.children().toList()
        for (child in children) {
            removedCount += removeEmptyElementsBottomUp(child, preserveTags, depth + 1)
        }

        // After processing children, check if this element itself should be removed
        // An element is considered empty if it has no children and no meaningful text
        val isEmpty = element.children().isEmpty() && element.ownText().isBlank()
        val shouldPreserve = preserveTags.contains(element.tagName().lowercase())

        if (isEmpty && !shouldPreserve) {
            element.remove()
            removedCount++
        }

        return removedCount
    }

    private fun transformHTMLTablesToMarkdown(text: String): String =
        TableMarkdownUtils.transformHTMLTablesToMarkdown(text)

    // ========== Batch Processing Methods ==========

    private val batchJson = Json { ignoreUnknownKeys = true }

    override fun prepareBatchRequest(
        requestId: String,
        tableHtml: String,
        auxiliaryInfo: String,
        @Suppress("UNUSED_PARAMETER") boundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): BatchContentRequest {
        // Clean HTML directly without bounding box injection
        // (same approach as interactive mode - LLMs understand HTML structure well)
        val cleanedHtml = cleanHtml(tableHtml)

        val userPrompt = buildString {
            appendLine("Auxiliary Info: $auxiliaryInfo")
            appendLine(cleanedHtml)
            appendLine("Please generate the response in JSON structured output")
        }

        return BatchContentRequest(
            requestId = requestId,
            modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId,
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            temperature = 1.0f
        ).withSchema(outputSchema) // Use same schema as interactive mode
    }

    override fun parseBatchResponse(responseText: String): TableInterpretationBatchResult {
        return try {
            val response = batchJson.decodeFromString<TableInterpretationResponse>(responseText)
            val classification = SnippetClassification.fromString(response.classification)
            
            // Convert HTML table to markdown programmatically (handles rowspan/colspan properly)
            val markdown = if (response.htmlTable.isNotBlank()) {
                transformHTMLTablesToMarkdown(response.htmlTable)
            } else {
                ""
            }
            
            val combinedMarkdown = combineMarkdownWithAdditionalInfo(classification, markdown, response.additionalInfo)
            TableInterpretationBatchResult(
                classification = classification,
                markdown = combinedMarkdown,
                additionalInfo = response.additionalInfo
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: {}", e.message)
            TableInterpretationBatchResult(
                classification = SnippetClassification.TABLE,
                markdown = "",
                additionalInfo = ""
            )
        }
    }
}