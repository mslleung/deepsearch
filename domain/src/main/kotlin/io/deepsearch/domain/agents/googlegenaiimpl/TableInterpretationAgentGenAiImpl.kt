package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ITableInterpretationAgent
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
        .description("Markdown representation of a tabular element with optional additional context")
        .properties(
            mapOf(
                "additionalInfo" to Schema.builder()
                    .type("STRING")
                    .description("Optional additional context, notes, or clarifications about the table that cannot be represented in the markdown structure (e.g., badges, labels, footnotes, ambiguities).")
                    .build(),
                "markdown" to Schema.builder()
                    .type("STRING")
                    .description("The table expressed in GitHub-flavored Markdown. Contains ONLY the markdown table itself, without any additional commentary or notes.")
                    .build(),
            )
        )
        .required(listOf("additionalInfo", "markdown"))
        .build()

    private val systemInstruction = """
        Convert the HTML table into GitHub-flavored Markdown.
        
        Use ds-bounding-box="left top right bottom" attributes to understand spatial layout when HTML structure is ambiguous.
    
        Rules:
        - Preserve row/column structure accurately
        - Include header row if exists
        - Adjust rows/columns if needed to fit markdown format while preserving meaning
        - Do not invent data - only use what's in the HTML
        - Capture ALL text with no information loss
        - Use emojis (✅ ❌) instead of HTML entities
        - For merged cells, duplicate values to corresponding cells
        - Coerce non-table HTML into tabular format if needed

        Output JSON with 2 fields:
        - "additionalInfo": ONLY for critical clarifications that CANNOT fit in the table. 
          Format: "Note: [fact]"
          Don't explain what the table shows or repeat table data.
        - "markdown": The markdown table.

        Output structure:
        {
          "additionalInfo": string,
          "markdown": string
        }
    """.trimIndent()

    @Serializable
    private data class TableInterpretationResponse(
        val additionalInfo: String,
        val markdown: String,
    )

    override suspend fun generate(input: TableInterpretationInput): TableInterpretationOutput {
        // Use pre-computed HTML and bounding boxes (derived from page snapshot)
        val tableHtml = input.tableHtml
        val boundingBoxes = input.boundingBoxes

        logger.debug("Interpreting table to markdown (html length {})", tableHtml.length)
        logger.debug("Using {} pre-computed bounding boxes", boundingBoxes.size)

        // Inject bounding box attributes into HTML
        val htmlWithBoundingBoxes = injectBoundingBoxes(tableHtml, boundingBoxes)

        // Clean HTML to reduce token usage and noise
        val cleanedHtml = cleanHtml(htmlWithBoundingBoxes)

        logger.debug("Cleaned HTML length: {} (original: {})", cleanedHtml.length, tableHtml.length)

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

        val combinedMarkdown = combineMarkdownWithAdditionalInfo(response.markdown, response.additionalInfo)
        
        logger.debug("Table interpretation complete: {} chars markdown, {} chars additionalInfo", 
            response.markdown.length, response.additionalInfo.length
        )
        return TableInterpretationOutput(
            markdown = combinedMarkdown,
            tokenUsage = tokenUsage
        )
    }

    private fun injectBoundingBoxes(html: String, boundingBoxes: Map<String, IBrowserPage.BoundingBox>): String {
        if (boundingBoxes.isEmpty()) {
            return html
        }

        try {
            // Parse the HTML fragment (table element)
            val doc = Jsoup.parseBodyFragment(html)
            doc.outputSettings().prettyPrint(false)

            // The table should be the first child in the body
            val root = doc.body().children().firstOrNull() ?: return html

            // Pre-compile regex for performance
            val xpathRegex = Regex("""([a-zA-Z0-9_\-:]+)\[(\d+)]""")

            // Tags relevant for table interpretation (structure and cell boundaries)
            // Includes semantic table elements and divs for CSS-based tables
            val relevantTags = setOf("table", "thead", "tbody", "tfoot", "tr", "td", "th", "div")

            for ((xpath, bbox) in boundingBoxes) {
                val width = bbox.right - bbox.left
                val height = bbox.bottom - bbox.top

                // If the element truly has 0 0 0 0 bounding box (or 0 size), do not inject as it has no meaning
                if (width <= 0.0 && height <= 0.0) {
                    continue
                }

                val bboxValue = "${bbox.left.toInt()} ${bbox.top.toInt()} ${bbox.right.toInt()} ${bbox.bottom.toInt()}"

                // XPath format: ./tagname[index]/tagname[index]/...
                val element = findElementByRelativeXPath(root, xpath, xpathRegex)

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
     * Find an element using a relative XPath expression starting from a root element.
     * XPath format: ./tagname[index]/tagname[index]/... or "." for the root itself
     */
    private fun findElementByRelativeXPath(
        root: org.jsoup.nodes.Element,
        xpath: String,
        regex: Regex
    ): org.jsoup.nodes.Element? {
        if (xpath == ".") {
            return root
        }

        if (!xpath.startsWith("./")) {
            return null
        }

        val parts = xpath.substring(2).split("/")
        var current = root

        for (part in parts) {
            // Parse "tagname[index]"
            val match = regex.find(part) ?: return null
            val tagName = match.groupValues[1]
            val index = match.groupValues[2].toInt()

            // Find the nth child with the matching tag name (1-based index)
            // Optimize: traverse children manually to avoid creating new lists
            var count = 0
            var found: org.jsoup.nodes.Element? = null

            val children = current.children()
            for (child in children) {
                if (child.tagName().equals(tagName, ignoreCase = true)) {
                    count++
                    if (count == index) {
                        found = child
                        break
                    }
                }
            }

            if (found == null) {
                return null
            }
            current = found
        }

        return current
    }

    private fun combineMarkdownWithAdditionalInfo(markdown: String, additionalInfo: String?): String {
        return if (additionalInfo.isNullOrBlank()) {
            markdown
        } else {
            // Append additional info as a simple note below the table (no blockquote formatting)
            // The additionalInfo should already be brief and start with "Note:" per the prompt
            val trimmedInfo = additionalInfo.trim()
            "$markdown\n\n*$trimmedInfo*"
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

        // Step 3: Strip attributes except whitelist
        doc.select("*").forEach { element ->
            val attributes = element.attributes().asList()
            val attrsToKeep = attributes.filter { attr ->
                attr.key == "ds-bounding-box" ||
                        attr.key == "colspan" ||
                        attr.key == "rowspan" ||
                        attr.key == "id" ||
                        attr.key == "class" ||
                        attr.key == "role" ||
                        attr.key == "scope" ||
                        attr.key.startsWith("data-")
            }
            element.clearAttributes()
            attrsToKeep.forEach { attr ->
                element.attr(attr.key, attr.value)
            }
        }

        // Step 4: Remove empty elements, preserving table structure
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

        return doc.body().html()
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
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): BatchContentRequest {
        // Inject bounding boxes into HTML (same as interactive mode)
        val htmlWithBoundingBoxes = injectBoundingBoxes(tableHtml, boundingBoxes)
        val cleanedHtml = cleanHtml(htmlWithBoundingBoxes)

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
            val combinedMarkdown = combineMarkdownWithAdditionalInfo(response.markdown, response.additionalInfo)
            TableInterpretationBatchResult(
                markdown = combinedMarkdown,
                additionalInfo = response.additionalInfo
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: {}", e.message)
            TableInterpretationBatchResult(
                markdown = "",
                additionalInfo = ""
            )
        }
    }
}
