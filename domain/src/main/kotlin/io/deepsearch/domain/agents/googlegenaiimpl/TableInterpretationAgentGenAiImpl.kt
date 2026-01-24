package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.SnippetClassification
import io.deepsearch.domain.agents.TableInterpretationBatchResult
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.agents.TableInterpretationOutput
import io.deepsearch.domain.agents.infra.ModelIds
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
        .description("Classification and markdown representation of an HTML snippet")
        .properties(
            mapOf(
                "classification" to Schema.builder()
                    .type("STRING")
                    .enum_(listOf("TABLE", "CARD", "LIST", "COOKIE_DECLARATION_TABLE", "HIDDEN_MOBILE_LAYOUT", "OTHERS"))
                    .description("Content type classification: TABLE (pricing, comparison tables), CARD (card-like structures), LIST (bullet points), COOKIE_DECLARATION_TABLE (cookie consent tables), HIDDEN_MOBILE_LAYOUT (hidden mobile-specific content), OTHERS (non-tabular content)")
                    .build(),
                "additionalInfo" to Schema.builder()
                    .type("STRING")
                    .description("Optional additional context, notes, or clarifications that cannot be represented in the markdown structure (e.g., badges, labels, footnotes, ambiguities).")
                    .build(),
                "markdown" to Schema.builder()
                    .type("STRING")
                    .description("The content expressed in GitHub-flavored Markdown. For COOKIE_DECLARATION_TABLE and HIDDEN_MOBILE_LAYOUT, leave empty.")
                    .build(),
            )
        )
        .required(listOf("classification", "additionalInfo", "markdown"))
        .build()

    private val systemInstruction = """
        You are given a HTML snippet that most likely contains a table.
        Your task is to convert it to GitHub-flavored Markdown.

        Content classification:
        - The snippet is determined to follow a grid-like structure based on spatial analysis
        - You should determine the content type based on HTML structure and content
        - Classify into:
          -- TABLE: Tabular data with rows and columns (e.g. pricing, comparison, specification tables)
          -- CARD: Card-like structures with repeated similar items
          -- LIST: Bullet points or numbered lists
          -- COOKIE_DECLARATION_TABLE: Cookie consent/declaration tables (legal boilerplate listing cookies, their purposes, providers, and expiry). These are typically found in privacy/cookie policies.
          -- HIDDEN_MOBILE_LAYOUT: Hidden mobile-specific layouts that duplicate visible desktop content. Look for CSS classes like "mobile", "sm:", "hidden", "collapsed" combined with duplicate content patterns.
          -- OTHERS: Navigation menus, form layouts, or other non-tabular content

        Special handling for removable content:
        - COOKIE_DECLARATION_TABLE and HIDDEN_MOBILE_LAYOUT will be removed from the document
        - For these classifications, set markdown to empty string ""
        - Set additionalInfo to briefly describe what was detected (e.g., "Cookie declaration table with 15 cookies listed")

        Markdown conversion rules:
        - For TABLE/CARD: Convert to markdown table preserving row/column structure
        - For LIST: Convert to markdown bullet/numbered list
        - For OTHERS: Structure into well-formatted markdown (headers, paragraphs, lists)
        - For COOKIE_DECLARATION_TABLE/HIDDEN_MOBILE_LAYOUT: Leave markdown empty
        - If the snippet contains mixed content, convert each relevant section accordingly to generate a well-structured markdown
        - Never return raw HTML tags in the markdown field. Always convert to proper markdown syntax.
        
        Table/Card conversion rules:
        - Preserve row/column structure accurately
        - Include header row if exists
        - Adjust rows/columns if needed to fit markdown format while preserving meaning
        - Do not invent data - only use what's in the HTML
        - Capture ALL text with no information loss
        - Use emojis (✅ ❌) instead of HTML entities
        - For merged cells, duplicate values to corresponding cells

        Output JSON with 3 fields:
        - "classification": string - one of "TABLE", "CARD", "LIST", "COOKIE_DECLARATION_TABLE", "HIDDEN_MOBILE_LAYOUT", "OTHERS"
        - "additionalInfo": ONLY for critical clarifications that CANNOT fit in markdown.
          Format: "Note: [fact]" - Empty string if not needed.
        - "markdown": The content expressed in GitHub-flavored Markdown. Empty for COOKIE_DECLARATION_TABLE and HIDDEN_MOBILE_LAYOUT.

        Output structure:
        {
          "classification": string,
          "additionalInfo": string,
          "markdown": string
        }
    """.trimIndent()

    @Serializable
    private data class TableInterpretationResponse(
        val classification: String,
        val additionalInfo: String,
        val markdown: String,
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

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
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
                                .thinkingBudget(0)
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
        val combinedMarkdown = combineMarkdownWithAdditionalInfo(classification, response.markdown, response.additionalInfo)
        
        logger.debug("Table interpretation complete: classification={}, {} chars markdown, {} chars additionalInfo", 
            classification, response.markdown.length, response.additionalInfo.length
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
     * - TABLE/CARD: Append additionalInfo without newline (on same line)
     * - LIST/OTHERS: Drop additionalInfo, return only markdown
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
                // No newline between markdown and additionalInfo
                "$markdown\n$trimmedInfo"
            }
            SnippetClassification.LIST, SnippetClassification.OTHERS -> {
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
            modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            temperature = 1.0f
        ).withSchema(outputSchema) // Use same schema as interactive mode
    }

    override fun parseBatchResponse(responseText: String): TableInterpretationBatchResult {
        return try {
            val response = batchJson.decodeFromString<TableInterpretationResponse>(responseText)
            val classification = SnippetClassification.fromString(response.classification)
            val combinedMarkdown = combineMarkdownWithAdditionalInfo(classification, response.markdown, response.additionalInfo)
            TableInterpretationBatchResult(
                classification = classification,
                markdown = combinedMarkdown,
                additionalInfo = response.additionalInfo
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: {}", e.message)
            TableInterpretationBatchResult(
                classification = SnippetClassification.OTHERS,
                markdown = "",
                additionalInfo = ""
            )
        }
    }
}