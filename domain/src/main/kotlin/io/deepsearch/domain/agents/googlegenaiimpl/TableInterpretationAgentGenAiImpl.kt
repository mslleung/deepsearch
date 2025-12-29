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
        You are given the HTML markup for a table-like region from a webpage and an auxiliary info describing 
        the table. Convert this table into clean, faithful GitHub-flavored Markdown with additional information.
        
        Each element in the HTML has been augmented with a ds-bounding-box attribute containing spatial coordinates
        in the format ds-bounding-box="left top right bottom". These coordinates are relative to the table element's top-left corner
        and can help you understand the spatial layout and relationships between elements.
    
        Note that HTML tables may not be in perfect row/column format due to styling etc. Bounding box is crucial
        for mapping elements that are out of place.
    
        Rules:
        - Preserve the table's row and column structure and order accurately.
        - Include a header row if one exists; otherwise infer a sensible header from the first row if appropriate.
        - The HTML table may not translate well into a 2-dimensional markdown table. 
          In that case please adjust the rows and columns while preserving the semantic meaning of the table.
        - Use the bounding box coordinates to better understand the spatial layout when the HTML structure is ambiguous.
        - Do not invent data. Only use what is present in the supplied HTML.
        - Make sure all table data is captured with no information loss. All text must be represented in the markdown output.
        - Make sure the markdown is valid. The resulting markdown should not contain HTML or HTML entities.
        - Prefer to use emojis such as ✅ over HTML entities like &#10004; or &#10008;.
        - If there is any ambiguity or information conflict, note them in the additionalInfo field.
        - Normalize whitespace; remove decorative or layout-only characters.
        - For merged cells, please duplicate the cell value to all corresponding cells in the markdown table.
        - If the HTML do not look like a table, interpret and coerce the data into tabular markdown format while preserving semantic meaning.
        - Provide the answer once

        The output must strictly conform to JSON structured output with 2 fields:
        - "additionalInfo": Any additional context that cannot fit in the table structure (badges, labels, footnotes, ambiguities, out-of-place elements).
        - "markdown": The table as a GitHub-flavored Markdown table.

        Example output:
        {
          "additionalInfo": "*   **Pro AI** includes \"Free Onboarding Support\".\n*   **Premium AI** includes \"Free Onboarding Support\" and is marked as \"Most Popular\".\n*   **Enterprise AI** includes \"🌟 AI Solution Engineer Support\"."
          "markdown": "| Feature | Free | Pro AI | Premium AI | Enterprise AI |\n|---|---|---|---|---|\n| **Description** | For individuals to discover the power of AI in transforming customer engagement | For small teams to centralize conversations and automate the basics with AI agents | For scaling businesses to grow with advanced automation, integration and analytics | For large organizations to access tailored solutions, top-tier security, and strategic support |\n| **Price** | Free | Starting from US$ 99/month | Starting from US$ 299/month | Let's talk |\n| **Call to Action** | Try Free Forever | Start for Free | Start for Free | Book a Demo |\n| **Key features** | 50 monthly active contacts | Up to 2,000 monthly active contacts | Up to 12,000 monthly active contacts | Custom number of monthly active contacts |\n| | Includes 3 user accounts | Includes 3 user accounts | Includes 10 user accounts | Custom number of user accounts |\n| | Test all core features without affecting your live business using the | Unlimited Broadcast | Analytics dashboards | Salesforce & custom integrations |\n| | | Unlimited Flow Builder usage | Webhook & API calls | Dedicated Customer success manager |\n| | | Unlimited contact storage | Role-based access control | PII masking |\n| | | Team Inbox | Advanced AI Agents with integrations | |\n| | | AI Agent | | |",
        }

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
            "$markdown\n**Additional Information:**\n$additionalInfo\n\n"
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
