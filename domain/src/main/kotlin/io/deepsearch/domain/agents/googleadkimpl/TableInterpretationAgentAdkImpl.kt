package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
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
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TableInterpretationAgentAdkImpl : ITableInterpretationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Markdown representation of a tabular element")
        .properties(
            mapOf(
                "markdown" to Schema.builder()
                    .type("STRING")
                    .description("The table expressed in GitHub-flavored Markdown.")
                    .build()
            )
        )
        .required(listOf("markdown"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("tableInterpretationAgent")
        description("Interpret a table region from a webpage and output Markdown")
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
            - If there is any ambiguity or information conflict, note them clearly under **Additional Information:**.
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

            **Output only the Markdown string, wrapped in JSON structured output**

            Expected output shape:
            {
                "markdown": "string"
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class TableInterpretationResponse(
        val markdown: String
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

        logger.debug("Cleaned HTML length: {} (original: {})", cleanedHtml.length, htmlWithBoundingBoxes.length)
        logger.debug(cleanedHtml)

        val response = retryLlmCall<TableInterpretationResponse> {
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

            val parts = buildList {
                add(Part.fromText("Auxiliary context: " + input.tableIdentification.auxiliaryInfo))
                add(Part.fromText(cleanedHtml))
            }

            val eventsFlow = runner.runAsync(
                session,
                Content.fromParts(*parts.toTypedArray()),
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

            llmResponse
        }

        val markdown = response.markdown.trim()
        logger.debug("Table interpreted to markdown ({} chars)", markdown.length)

        return TableInterpretationOutput(markdown = markdown)
    }

    /**
     * Cleans HTML to optimize for LLM consumption by removing noise and non-content elements.
     */
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
        val removedCount = removeEmptyElementsBottomUp(doc.body(), preserveTags)
        logger.trace("Removed {} empty elements in single pass", removedCount)

        return doc.body().html()
    }

    /**
     * Removes empty elements using a bottom-up (post-order) traversal.
     * This processes children before parents, allowing nested empty elements to be removed in a single pass.
     * 
     * @param element The root element to process
     * @param preserveTags Set of tag names that should never be removed, even if empty
     * @return The count of removed elements
     */
    private fun removeEmptyElementsBottomUp(
        element: org.jsoup.nodes.Element,
        preserveTags: Set<String>
    ): Int {
        var removedCount = 0
        
        // Process all children first (post-order traversal)
        // Use toList() to avoid ConcurrentModificationException when removing elements
        val children = element.children().toList()
        for (child in children) {
            removedCount += removeEmptyElementsBottomUp(child, preserveTags)
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

    /**
     * Injects bounding box coordinates into HTML elements.
     * Each element receives a ds-bounding-box attribute with format "left top right bottom".
     */
    private fun injectBoundingBoxes(
        html: String,
        boundingBoxes: Map<String, io.deepsearch.domain.browser.IBrowserPage.BoundingBox>
    ): String {
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

            for ((xpath, bbox) in boundingBoxes) {
                val bboxValue = "${bbox.left.toInt()} ${bbox.top.toInt()} ${bbox.right.toInt()} ${bbox.bottom.toInt()}"

                // XPath format: ./tagname[index]/tagname[index]/...
                val element = findElementByRelativeXPath(root, xpath, xpathRegex)
                element?.attr("ds-bounding-box", bboxValue)
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
}


