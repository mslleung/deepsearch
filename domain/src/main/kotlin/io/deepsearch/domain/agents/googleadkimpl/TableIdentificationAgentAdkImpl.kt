package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableIdentificationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.services.ICssSelectorConstructionService
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TableIdentificationAgentAdkImpl(
    private val cssSelectorConstructionService: ICssSelectorConstructionService
) : ITableIdentificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("List of table IDs for table roots")
        .properties(
            mapOf(
                "tables" to Schema.builder()
                    .type("ARRAY")
                    .description("Array of table identifications with data-ds-id values")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                mapOf(
                                    "id" to Schema.builder().type("STRING")
                                        .description("The data-ds-id value of the table root element (e.g., 'ds-table-0', 'ds-table-5').")
                                        .build(),
                                    "auxiliaryInfo" to Schema.builder().type("STRING")
                                        .description("The auxiliary info for the table.").build()
                                )
                            )
                            .required(listOf("id", "auxiliaryInfo"))
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("tables"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("tableIdentificationAgent")
        description("Identify tables in webpage using cleaned HTML, return their stable IDs")
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
            Your task is to identify all table structures in the provided webpage and return the stable identifiers of their root containers.

            Inputs:
            - Cleaned HTML structure of the webpage
            
            Each element in the HTML has been augmented with a ds-bounding-box attribute containing spatial coordinates
            in the format ds-bounding-box="left top right bottom". These coordinates help you understand the spatial layout
            and relationships between elements.

            Instructions:
            - Analyze the HTML to identify every table-like structure in the webpage
            - Always target the table root containers instead of individual rows and columns
            - In the event of nested tables/grids, target the outermost wrapping parent only
            - Modern websites may design tables purely using <div> styling or structure, you need to identify them based on semantic meaning
            - Use the bounding box coordinates to better understand the spatial layout of the HTML structure.
            - For every table you find, return the data-ds-id attribute value (e.g., "ds-table-5") pointing to the root container.
            - Additionally, generate a brief auxiliaryInfo based on the webpage context, it should contain:
              - The table's description
              - The column headers

            Expected output shape:
            {
                "tables": [
                    {
                        "id": "string",
                        "auxiliaryInfo": "string"
                    }
                ]
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class TableIdentificationResponse(
        val tables: List<LlmTableResult>
    )

    @Serializable
    private data class LlmTableResult(
        val id: String,
        val auxiliaryInfo: String
    )

    override suspend fun generate(input: TableIdentificationInput): TableIdentificationOutput {
        // Step 1: Get webpage HTML and bounding boxes
        val originalHtml = input.webpage.getFullHtml()
        val boundingBoxes = input.webpage.getBoundingBoxesByCssSelector("body")

        logger.debug("Table identification for HTML ({} bytes)", originalHtml.length)
        logger.debug("Got {} bounding boxes for table identification", boundingBoxes.size)

        // Step 2: Inject stable identifiers into jsoup copy
        val htmlWithIds = injectStableIdentifiers(originalHtml, "ds-table")

        // Step 3: Inject bounding boxes into jsoup copy
        val htmlWithIdsAndBboxes = injectBoundingBoxes(htmlWithIds, boundingBoxes)

        // Programmatic extraction of semantic tables to reduce LLM input
        val (programmaticTables, reducedHtml) = extractSemanticTables(htmlWithIds)
        logger.debug("Programmatically extracted {} semantic tables", programmaticTables.size)

        // Step 4: Clean HTML (after identifier and bbox injection)
        val cleanedHtml = cleanHtml(htmlWithIdsAndBboxes)

        if (cleanedHtml.isEmpty()) {
            return TableIdentificationOutput(tables = emptyList())
        }

        // Step 5: Pass to LLM
        val response = retryLlmCall<TableIdentificationResponse> {
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
                    Part.fromText(reducedHtml)
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

            llmResponse
        }

        val allTableResults = programmaticTables + response.tables
        logger.debug(
            "Total table identification found {} table IDs ({} programmatic, {} LLM)",
            allTableResults.size, programmaticTables.size, response.tables.size
        )

        // Step 6 & 7: Reconstruct CSS selectors and inject into webpage
        val tableIdentifications = allTableResults.mapNotNull { llmResult ->
            convertToTableIdentification(llmResult, htmlWithIds, input)
        }

        logger.debug(
            "Table identification produced {} table identifications",
            tableIdentifications.size
        )

        return TableIdentificationOutput(tables = tableIdentifications)
    }

    /**
     * Converts an LLM table result (ID + auxiliaryInfo) to a TableIdentification.
     * Uses the ID to construct a CSS selector, injects the identifier into the actual webpage,
     * and returns a table identification with a selector that points to the injected identifier.
     */
    private suspend fun convertToTableIdentification(
        llmResult: LlmTableResult,
        htmlWithIds: String,
        input: TableIdentificationInput
    ): TableIdentification? {
        // Step 6: Reconstruct CSS selector using identifier
        val cssSelector = cssSelectorConstructionService.constructCssSelectorFromIdentifier(
            identifier = llmResult.id,
            htmlWithIdentifiers = htmlWithIds
        )

        if (cssSelector == null) {
            logger.warn(
                "Skipping table with ID '{}' - could not construct valid CSS selector",
                llmResult.id
            )
            return null
        }

        logger.debug("Constructed CSS selector '{}' for identifier '{}'", cssSelector, llmResult.id)

        // Step 7: Inject identifier into actual webpage
        input.webpage.injectAttributeByCssSelector(cssSelector, "data-ds-id", llmResult.id)

        // Return table identification with selector that matches the injected identifier
        return TableIdentification(
            cssSelector = "[data-ds-id=\"${llmResult.id}\"]",
            auxiliaryInfo = llmResult.auxiliaryInfo
        )
    }

    private fun extractSemanticTables(htmlWithIds: String): Pair<List<LlmTableResult>, String> {
        val doc = Jsoup.parse(htmlWithIds)
        val tables = mutableListOf<LlmTableResult>()

        // Select ONLY <table> elements with data-ds-id
        // NOTE: role="table" is intentionally ignored as per user requirement
        val tableElements = doc.select("table[data-ds-id]")

        tableElements.forEach { element ->
            val id = element.attr("data-ds-id")
            // Extract simple auxiliary info (e.g., caption or summary)
            val caption = element.select("caption").text()
            val summary = element.attr("summary")
            // Use caption or summary or empty string
            val auxiliaryInfo = when {
                caption.isNotBlank() -> caption
                summary.isNotBlank() -> summary
                else -> ""
            }

            tables.add(LlmTableResult(id, auxiliaryInfo))
            element.remove() // Remove from DOM to reduce LLM input
        }

        return Pair(tables, doc.outerHtml())
    }

    private fun cleanHtml(rawHtml: String): String {
        val doc: Document = Jsoup.parse(rawHtml)

        // Step 1: Remove noise elements that don't contribute to structure
        doc.select(
            "script, style, noscript, template, svg, canvas, meta, link, iframe, object, embed, " +
                    "head, title, base, " +
                    "form, input, button, select, textarea, label, fieldset, legend, " +
                    "nav, header, footer, aside, " +
                    "img, video, audio, source, track, picture"
        ).remove()

        // Remove comments and processing instructions
        doc.select("*").forEach { element ->
            val nodesToRemove = element.childNodes().filter { node ->
                node.nodeName() == "#comment" || node.nodeName() == "#pi"
            }
            nodesToRemove.forEach { node -> node.remove() }
        }

        // Step 2: Strip attributes aggressively - bounding boxes replace need for class/id
        // Keep only: role, colspan, rowspan, scope, data-testid, data-ds-id, ds-bounding-box
        doc.select("*").forEach { element ->
            val originalAttrs = element.attributes().asList()
            val essentialAttrs = originalAttrs.filter { attr ->
                attr.key == "id" ||
                        attr.key == "class" ||
                        attr.key == "role" ||
                        attr.key == "colspan" ||
                        attr.key == "rowspan" ||
                        attr.key == "scope" ||
                        attr.key == "data-testid" ||
                        attr.key == "data-ds-id"
                        attr.key == "ds-bounding-box"
            }

            element.clearAttributes()

            // Restore filtered attributes
            essentialAttrs.forEach { attr ->
                element.attr(attr.key, attr.value)
            }
        }

        // Step 3: Truncate text content but keep enough for pattern recognition
        // Tables have distinct patterns: numbers, dates, structured text
        // Keep more text than semantic identification (20 chars vs 15) to preserve data patterns
        doc.select("*").forEach { element ->
            element.textNodes().forEach { textNode ->
                val text = textNode.text().replace("\\s+".toRegex(), " ").trim()
                if (text.isNotEmpty()) {
                    // 20 chars is enough to see patterns: "2024-01-15", "Price: $99.99", "Employee: John"
                    val shortened = if (text.length > 20) text.take(20) + "..." else text
                    textNode.text(shortened)
                }
            }
        }

        // Step 4: Remove carousel/slider clones (major source of duplication)
        doc.select(".slick-cloned, [class*=swiper-slide-duplicate], [data-cloned=true]").remove()

        // Step 5: Remove duplicate mobile/desktop navigation structures
        val mobileNavSelectors = listOf(
            ".mobile-nav", ".mobile-menu", "[class*=mobile-nav]", "[class*=mobile-menu]",
            ".nav-mobile", ".menu-mobile", "[id*=mobile-nav]", "[id*=mobile-menu]"
        )
        val desktopNavSelectors = listOf(
            ".desktop-nav", ".primary-nav", "[class*=desktop-nav]", "[class*=primary-nav]",
            ".nav-desktop", ".main-nav", "[id*=desktop-nav]", "[id*=primary-nav]"
        )

        val hasMobileNav = doc.select(mobileNavSelectors.joinToString(",")).isNotEmpty()
        val hasDesktopNav = doc.select(desktopNavSelectors.joinToString(",")).isNotEmpty()
        if (hasMobileNav && hasDesktopNav) {
            doc.select(mobileNavSelectors.joinToString(",")).remove()
            logger.debug("Removed duplicate mobile navigation")
        }

        // Step 6: Remove empty elements iteratively to compact structure
        // This significantly reduces HTML size without losing meaningful structure
        val emptyElements = doc.select("*").filter { element ->
            element.children().isEmpty() &&
                    element.ownText().isBlank() &&
                    element.attr("role").isEmpty() &&
                    element.attr("colspan").isEmpty() &&
                    element.attr("rowspan").isEmpty() &&
                    element.attr("ds-bounding-box").isEmpty()
        }

        if (emptyElements.isNotEmpty()) {
            emptyElements.forEach { it.remove() }
            logger.debug("Removed {} empty elements", emptyElements.size)
        }

        val cleanedHtml = doc.outerHtml()
        logger.debug(
            "Cleaned HTML: {} chars (original: ~{} chars)",
            cleanedHtml.length, rawHtml.length
        )
        return cleanedHtml
    }

    /**
     * Injects stable identifiers into potential table elements for LLM processing.
     *
     * @param cleanedHtml The cleaned HTML (without IDs)
     * @param idPrefix The prefix for generated IDs (e.g., "ds-table")
     * @return HTML with injected data-ds-id attributes
     */
    private fun injectStableIdentifiers(cleanedHtml: String, idPrefix: String): String {
        val doc: Document = Jsoup.parse(cleanedHtml)

        var idCounter = 0
        doc.select("table, div, section, article, main, aside, ul, ol, dl").forEach { element ->
            element.attr("data-ds-id", "$idPrefix-${idCounter++}")
        }

        return doc.outerHtml()
    }

    /**
     * Injects bounding box coordinates into HTML elements.
     * Each element receives a ds-bounding-box attribute with format "left top right bottom".
     * Only injects on elements that could potentially be table roots.
     */
    private fun injectBoundingBoxes(
        html: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): String {
        if (boundingBoxes.isEmpty()) {
            return html
        }

        try {
            // Parse the HTML
            val doc = Jsoup.parse(html)
            doc.outputSettings().prettyPrint(false)

            // The body should be the root
            val root = doc.body()

            // Pre-compile regex for performance
            val xpathRegex = Regex("""([a-zA-Z0-9_\-:]+)\[(\d+)\]""")
            
            // Tags relevant for table identification (potential table root containers)
            // Includes semantic table elements and common container elements used for CSS-based tables
            val relevantTags = setOf("table", "div", "section", "article", "main", "aside", "ul", "ol", "dl")

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
                
                // Only inject bounding boxes on elements that could be table roots
                if (element != null && element.tagName() in relevantTags) {
                    element.attr("ds-bounding-box", bboxValue)
                }
            }

            // Return the full HTML
            return doc.outerHtml()
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