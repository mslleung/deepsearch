package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.agents.TableInterpretationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TableInterpretationAgentGenAiImpl(
    private val client: com.google.genai.Client
) : ITableInterpretationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Markdown representation of a tabular element")
        .properties(
            mapOf(
                "markdown" to Schema.builder()
                    .type("STRING")
                    .description("The table expressed in GitHub-flavored Markdown.")
                    .build(),
            )
        )
        .required(listOf("markdown"))
        .build()

    private val systemInstruction = """
        You are given the HTML markup for a table-like region from a webpage and an auxiliary info describing 
        the table. Convert this table into clean, faithful GitHub-flavored Markdown.
        
        Each element in the HTML has been augmented with a ds-bounding-box attribute containing spatial coordinates
        in the format ds-bounding-box="left top right bottom". These coordinates are relative to the table element's top-left corner
        and can help you understand the spatial layout and relationships between elements.
    
        Note that HTML tables may not be in perfect row/column format due to styling etc. Bounding box is crucial
        for mapping elements that are out of place. For these elements, you can simply add a paragraph after the markdown table 
        to describe them under **Additional Information:**.
    
        Rules:
        - Preserve the table's row and column structure and order accurately.
        - Include a header row if one exists; otherwise infer a sensible header from the first row if appropriate.
        - The HTML table may not translate well into a 2-dimensional markdown table. 
          In that case please adjust the rows and columns while preserving the semantic meaning of the table.
        - Use the bounding box coordinates to better understand the spatial layout when the HTML structure is ambiguous.
        - Do not invent data. Only use what is present in the supplied HTML.
        - Make sure all table data is captured with no information loss. All text must be represented in the markdown.
        - Make sure the markdown is valid. The resulting markdown should not contain HTML.
        - If there is any ambiguity or information conflict, note them clearly inside the table and explain under **Additional Information:**.
        - Normalize whitespace; remove decorative or layout-only characters.
        - For merged cells, please duplicate the cell value to all corresponding cells in the markdown table.
    
        Example markdown output:
        | Feature | Free | Pro AI | Premium AI | Enterprise AI |
        |---|---|---|---|---|
        | **Description** | For individuals to discover the power of AI in transforming customer engagement | For small teams to centralize conversations and automate the basics with AI agents | For scaling businesses to grow with advanced automation, integration and analytics | For large organizations to access tailored solutions, top-tier security, and strategic support |
        | **Price** | Free | Starting from US$ 99/month | Starting from US$ 299/month | Let’s talk |
        | **Call to Action** | Try Free Forever | Start for Free | Start for Free | Book a Demo |
        | **Key features** | 50 monthly active contacts | Up to 2,000 monthly active contacts | Up to 12,000 monthly active contacts | Custom number of monthly active contacts |
        | | Includes 3 user accounts | Includes 3 user accounts | Includes 10 user accounts | Custom number of user accounts |
        | | Test all core features without affecting your live business using the | Unlimited Broadcast | Analytics dashboards | Salesforce & custom integrations |
        | | | Unlimited Flow Builder usage | Webhook & API calls | Dedicated Customer success manager |
        | | | Unlimited contact storage | Role-based access control | PII masking |
        | | | Team Inbox | Advanced AI Agents with integrations | |
        | | | AI Agent | | |
        **Additional Information:**
        *   **Pro AI** includes "Free Onboarding Support".
        *   **Premium AI** includes "Free Onboarding Support" and is marked as "Most Popular".
        *   **Enterprise AI** includes "🌟 AI Solution Engineer Support".
    """.trimIndent()

    @Serializable
    private data class TableInterpretationResponse(
        val markdown: String,
    )

    override suspend fun generate(input: TableInterpretationInput): TableInterpretationOutput {
        // Extract HTML and bounding boxes from the webpage
        val tableHtml = input.webpage.getElementHtmlByCssSelector(input.tableIdentification.cssSelector)
        val boundingBoxes = input.webpage.getBoundingBoxesByCssSelector(input.tableIdentification.cssSelector)

        logger.debug("Interpreting table to markdown (html length {})", tableHtml.length)
        logger.debug("Got {} bounding boxes", boundingBoxes.size)

        // Inject bounding box attributes into HTML
        val htmlWithBoundingBoxes = injectBoundingBoxes(tableHtml, boundingBoxes)

        // Clean HTML to reduce token usage and noise
        val cleanedHtml = cleanHtml(htmlWithBoundingBoxes)

        logger.debug("Cleaned HTML length: {} (original: {})", cleanedHtml.length, tableHtml.length)

        val userPrompt = buildString {
            appendLine("Auxiliary Info: ${input.tableIdentification.auxiliaryInfo}")
            appendLine(cleanedHtml)
        }

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = retryLlmCall<TableInterpretationResponse> {
            val result = client.models.generateContent(
                modelId,
                userPrompt,
                GenerateContentConfig.builder()
                    .temperature(0F)
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

        logger.debug("Table interpretation complete: {} chars", response.markdown.length)
        return TableInterpretationOutput(
            markdown = response.markdown,
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
            val xpathRegex = Regex("""([a-zA-Z0-9_\-:]+)\[(\d+)\]""")

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

    private fun cleanHtml(rawHtml: String): String {
        val doc = Jsoup.parseBodyFragment(rawHtml)
        doc.outputSettings().prettyPrint(false)

        // Step 1: Remove noise elements
        doc.select(
            "script, style, noscript, template, svg, canvas, meta, link, iframe, object, embed, " +
                    "head, title, base, " +
                    "img, video, audio, source, track, picture, " +
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
}


