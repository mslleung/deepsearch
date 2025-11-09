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
import io.deepsearch.domain.agents.infra.decodeFromStringWithCodeBlocks
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TableIdentificationAgentAdkImpl : ITableIdentificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("List of HTML snippets for table roots")
        .properties(
            mapOf(
                "tables" to Schema.builder()
                    .type("ARRAY")
                    .description("Array of HTML snippet strings for table root elements")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                mapOf(
                                    "htmlSnippet" to Schema.builder().type("STRING")
                                        .description("The complete HTML of the table element, including opening tag, all content, and closing tag (e.g., '<table class=\"data-table\" id=\"results\"><tr><td>...</td></tr></table>').")
                                        .build(),
                                    "auxiliaryInfo" to Schema.builder().type("STRING")
                                        .description("The auxiliary info for the table.").build()
                                )
                            )
                            .required(listOf("htmlSnippet", "auxiliaryInfo"))
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("tables"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("tableIdentificationAgent")
        description("Identify tables in webpage using screenshot and cleaned HTML, return HTML snippets of their root containers")
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
            Your task is to identify all tables in the provided webpage and extract their HTML snippets.

            Inputs:
            - A screenshot of the full webpage
            - Cleaned HTML structure of the webpage

            Instructions:
            - Analyze both the screenshot and HTML to identify every table in the webpage
            - Look for both standard HTML table elements (<table>, <tr>, <td>, <th>) and CSS-based table layouts (divs with table-like styling).
            - For every table you find, extract the complete HTML of the table element (opening tag + all nested content + closing tag). This must include the full table structure, not just the opening tag or first row.
            - Additionally, extract auxiliaryInfo using surrounding text such as table headers and captions to provide extra information for understanding the table.

            Expected output shape:
            {
                "tables": [
                    {
                        "htmlSnippet": "string",
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
        val htmlSnippet: String,
        val auxiliaryInfo: String
    )

    /**
     * Constructs CSS selectors from an HTML snippet by parsing the root element's attributes
     * and finding all matching elements in the full HTML DOM using hierarchical parent context.
     * Only generates selectors for elements with matching structural hierarchy.
     * 
     * @param htmlSnippet The complete HTML of the table element (from cleaned HTML)
     * @param cleanedHtml The cleaned HTML document that the LLM saw
     * @param fullHtml The full HTML document (original, uncleaned)
     * @return List of CSS selectors, one for each matching element. Empty list if snippet is invalid.
     */
    private fun constructCssSelectorsFromSnippet(htmlSnippet: String, cleanedHtml: String, fullHtml: String): List<String> {
        return try {
            // Parse the snippet to extract the root element's attributes
            val snippetDoc = Jsoup.parseBodyFragment(htmlSnippet)
            val rootElement = snippetDoc.body().child(0)
            
            val tagName = rootElement.tagName()
            val id = rootElement.attr("id")
            val classes = rootElement.classNames()
            
            // Build base CSS selector for the target element
            val baseSelector = buildBaseSelector(tagName, id, classes)
            
            logger.debug("Constructed base selector: {} from snippet", baseSelector)
            
            // First, find matching elements in the CLEANED HTML (which the LLM saw)
            val cleanedDoc = Jsoup.parse(cleanedHtml)
            val cleanedMatchingElements = cleanedDoc.select(baseSelector)
            
            if (cleanedMatchingElements.isEmpty()) {
                logger.warn("No elements found in cleaned HTML for selector: {}", baseSelector)
                return emptyList()
            }
            
            // Filter by structural match in cleaned HTML
            val structurallyMatchingElements = cleanedMatchingElements.filter { candidate ->
                hasMatchingStructure(rootElement, candidate)
            }
            
            logger.debug("Filtered {} candidates to {} structurally-matching elements in cleaned HTML", 
                cleanedMatchingElements.size, structurallyMatchingElements.size)
            
            if (structurallyMatchingElements.isEmpty()) {
                logger.warn("No structurally-matching elements found for snippet: {}", htmlSnippet.take(100))
                return emptyList()
            }
            
            // Now find the same elements in the ORIGINAL HTML using their selectors
            val fullDoc = Jsoup.parse(fullHtml)
            
            // If single match in cleaned HTML, use the base selector
            if (structurallyMatchingElements.size == 1) {
                logger.debug("Single structurally-matching element found for selector: {}", baseSelector)
                return listOf(baseSelector)
            }
            
            // Multiple matches: construct hierarchical selectors from cleaned HTML elements
            // but validate they work on the original HTML
            return structurallyMatchingElements.mapNotNull { cleanedElement ->
                val hierarchicalSelector = buildHierarchicalSelector(cleanedElement, cleanedDoc)
                
                // Verify the selector works on original HTML
                val originalMatches = fullDoc.select(hierarchicalSelector)
                if (originalMatches.isEmpty()) {
                    logger.warn("Selector {} from cleaned HTML doesn't match original HTML", hierarchicalSelector)
                    null
                } else {
                    hierarchicalSelector
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to construct CSS selector from HTML snippet: {}", htmlSnippet.take(100), e)
            emptyList()
        }
    }
    
    /**
     * Builds a base CSS selector for an element using its tag, id, and classes.
     */
    private fun buildBaseSelector(tagName: String, id: String, classes: Set<String>): String {
        return when {
            id.isNotBlank() -> "#$id"
            classes.isNotEmpty() -> "$tagName.${classes.joinToString(".")}"
            else -> tagName
        }
    }
    
    /**
     * Compares structural similarity between snippet element and candidate element.
     * Both elements are from cleaned HTML, so we can do a direct structural comparison.
     * 
     * @param snippetElement The root element from the LLM-provided HTML snippet (from cleaned HTML)
     * @param candidateElement The candidate element from cleaned HTML
     * @return true if structures match exactly
     */
    private fun hasMatchingStructure(snippetElement: Element, candidateElement: Element): Boolean {
        // Extract child tag hierarchy for comparison
        fun extractStructure(element: Element, depth: Int, maxDepth: Int): List<String> {
            if (depth > maxDepth) return emptyList()
            
            val structure = mutableListOf<String>()
            
            // Add immediate children tags with depth prefix
            element.children().forEach { child ->
                structure.add("${depth}:${child.tagName()}")
                // Recursively add nested structure
                val childStructure = extractStructure(child, depth + 1, maxDepth)
                structure.addAll(childStructure)
            }
            
            return structure
        }

        // if a candidate in the original html has the base selector and the first 1000 characters look exactly the same, consider it a match
        val matches = snippetElement.outerHtml().take(1000) == candidateElement.outerHtml().take(1000)

        return matches
    }
    
    /**
     * Builds a hierarchical CSS selector for an element by walking up the DOM tree
     * to find unique ancestors and constructing a specific path.
     * 
     * Strategy:
     * 1. Walk up to 5 levels to find a unique ancestor (with id or distinctive classes)
     * 2. Build a selector path from that ancestor to the target element
     * 3. Include positional selectors (:nth-of-type or :nth-child) when needed for uniqueness
     * 
     * @param element The target element to build a selector for
     * @param doc The full document (for validating selector uniqueness)
     * @return A hierarchical CSS selector, or null if unable to construct a unique one
     */
    private fun buildHierarchicalSelector(element: Element, doc: Document): String {
        val selectorParts = mutableListOf<String>()
        var currentElement: Element? = element
        var foundUniqueAncestor = false
        val maxLevels = 5
        var level = 0
        
        // Walk up the tree to build the selector path
        while (currentElement != null && level < maxLevels) {
            val tagName = currentElement.tagName()
            val id = currentElement.attr("id")
            val classes = currentElement.classNames()
            
            // Build selector part for this element
            val selectorPart = when {
                // If element has an ID, use it as an anchor point
                id.isNotBlank() -> {
                    foundUniqueAncestor = true
                    "#$id"
                }
                // If element has classes, include them
                classes.isNotEmpty() -> {
                    val classSelector = "$tagName.${classes.joinToString(".")}"
                    // Check if this selector is unique at this level
                    val parent = currentElement.parent()
                    if (parent != null) {
                        val siblingsMatching = parent.children().filter { sibling ->
                            sibling.tagName() == tagName && sibling.classNames() == classes
                        }
                        if (siblingsMatching.size > 1) {
                            // Need positional selector
                            val position = siblingsMatching.indexOf(currentElement) + 1
                            "$classSelector:nth-of-type($position)"
                        } else {
                            classSelector
                        }
                    } else {
                        classSelector
                    }
                }
                // No id or classes, use tag with position
                else -> {
                    val parent = currentElement.parent()
                    if (parent != null) {
                        val siblingsOfSameType = parent.children().filter { it.tagName() == tagName }
                        if (siblingsOfSameType.size > 1) {
                            val position = siblingsOfSameType.indexOf(currentElement) + 1
                            "$tagName:nth-of-type($position)"
                        } else {
                            tagName
                        }
                    } else {
                        tagName
                    }
                }
            }
            
            selectorParts.add(0, selectorPart)
            
            // If we found a unique anchor (ID), stop here
            if (foundUniqueAncestor) {
                break
            }
            
            // Move up to parent
            currentElement = currentElement.parent()
            level++
            
            // Stop at body or html
            if (currentElement?.tagName() == "body" || currentElement?.tagName() == "html") {
                break
            }
        }
        
        // Construct the final selector with " > " combinator for direct child relationships
        val hierarchicalSelector = selectorParts.joinToString(" > ")
        
        // Validate that this selector is unique in the document
        val matches = doc.select(hierarchicalSelector)
        if (matches.size != 1) {
            logger.warn("Hierarchical selector '{}' matches {} elements, expected 1", hierarchicalSelector, matches.size)
            // Return it anyway, as it's still more specific than the base selector
        }
        
        logger.debug("Built hierarchical selector: {}", hierarchicalSelector)
        return hierarchicalSelector
    }

    override suspend fun generate(input: TableIdentificationInput): TableIdentificationOutput {
        logger.debug("Table identification for HTML (screenshot: {} bytes)", input.screenshotBytes.size)

        val cleanedHtml = cleanHtml(input.html)

        if (cleanedHtml.isEmpty()) {
            return TableIdentificationOutput(tables = emptyList())
        }

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
                Part.fromBytes(input.screenshotBytes, input.mimetype.value),
                Part.fromText("CLEANED_HTML:\n$cleanedHtml")
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

        val response = Json.decodeFromStringWithCodeBlocks<TableIdentificationResponse>(llmResponse)

        logger.debug("Table identification found {} HTML snippets from LLM", response.tables.size)

        // Convert HTML snippets to CSS selectors and expand if multiple matches
        // Note: Snippets come from cleaned HTML, match against cleaned, but selectors work on original HTML
        val tableIdentifications = response.tables.flatMap { llmResult ->
            val cssSelectors = constructCssSelectorsFromSnippet(llmResult.htmlSnippet, cleanedHtml, input.html)
            
            if (cssSelectors.isEmpty()) {
                logger.warn("Skipping table with snippet '{}' - could not construct valid CSS selector", 
                    llmResult.htmlSnippet.take(100))
                emptyList()
            } else {
                cssSelectors.map { cssSelector ->
                    TableIdentification(
                        htmlSnippet = llmResult.htmlSnippet,
                        cssSelector = cssSelector,
                        auxiliaryInfo = llmResult.auxiliaryInfo
                    )
                }
            }
        }

        logger.debug("Table identification produced {} table identifications (after expanding duplicates)", 
            tableIdentifications.size)

        return TableIdentificationOutput(tables = tableIdentifications)
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

        // Step 2: Strip attributes to essentials for table identification
        // Keep id, class, role for semantic meaning and XPath uniqueness
        // Keep table-specific attributes (colspan, rowspan) for structure
        // Keep data-* attributes as they often indicate structure
        doc.select("*").forEach { element ->
            val originalAttrs = element.attributes().asList()
            val essentialAttrs = originalAttrs.filter { attr ->
                attr.key == "id" || 
                attr.key == "class" || 
                attr.key == "role" || 
                attr.key == "colspan" || 
                attr.key == "rowspan" || 
                attr.key == "scope" ||
                attr.key.startsWith("data-")
            }
            
            element.clearAttributes()
            
            // Restore filtered attributes
            essentialAttrs.forEach { attr ->
                when (attr.key) {
                    "id" -> {
                        // Truncate very long IDs (generated IDs can be huge)
                        val value = if (attr.value.length <= 50) {
                            attr.value
                        } else {
                            attr.value.take(30) + "..."
                        }
                        element.attr("id", value)
                    }
                    "class" -> {
                        // Keep all classes but limit to first 5 to reduce bloat
                        val classes = attr.value.split("\\s+".toRegex()).take(5)
                        if (classes.isNotEmpty()) {
                            element.attr("class", classes.joinToString(" "))
                        }
                    }
                    "data-testid", "data-test", "data-qa" -> {
                        // Keep test IDs - they often indicate semantic structure
                        element.attr(attr.key, attr.value)
                    }
                    else -> {
                        // Keep other essential attributes as-is
                        element.attr(attr.key, attr.value)
                    }
                }
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
        var changed = true
        var iterations = 0
        while (changed && iterations < 5) { // Limit iterations for performance
            changed = false
            iterations++
            
            val emptyElements = doc.select("*").filter { element ->
                element.children().isEmpty() &&
                element.ownText().isBlank() &&
                element.attr("id").isEmpty() &&
                element.attr("class").isEmpty() &&
                element.attr("role").isEmpty() &&
                element.attr("colspan").isEmpty() &&
                element.attr("rowspan").isEmpty()
            }
            
            if (emptyElements.isNotEmpty()) {
                emptyElements.forEach { it.remove() }
                changed = true
                logger.debug("Iteration {}: Removed {} empty elements", iterations, emptyElements.size)
            }
        }

        val cleanedHtml = doc.outerHtml()
        logger.debug("Cleaned HTML: {} chars (original: ~{} chars)",
            cleanedHtml.length, rawHtml.length)
        return cleanedHtml
    }

    /**
     * Sample repeated sibling patterns: if many siblings have identical structure,
     * keep only first 3 as examples and remove the rest to reduce HTML size.
     * This preserves the pattern for LLM while removing redundancy.
     */
    private fun sampleRepeatedSiblings(parent: Element) {
        if (parent.children().size < 5) return // Not worth analyzing small groups
        
        // Group siblings by structural signature (tag + classes)
        val siblingGroups = mutableMapOf<String, MutableList<Element>>()
        parent.children().forEach { child ->
            val signature = "${child.tagName()}:${child.classNames().sorted().joinToString(",")}"
            siblingGroups.getOrPut(signature) { mutableListOf() }.add(child)
        }
        
        // For groups with many identical siblings, keep only first 3
        var removedCount = 0
        siblingGroups.forEach { (_, siblings) ->
            if (siblings.size >= 5) {
                // Keep first 3, remove the rest
                siblings.drop(3).forEach { 
                    it.remove()
                    removedCount++
                }
            }
        }
        
        if (removedCount > 0) {
            logger.debug("Sampled repeated siblings: removed {} duplicate elements", removedCount)
        }
        
        // Recurse into remaining children
        parent.children().forEach { child ->
            sampleRepeatedSiblings(child)
        }
    }

    /**
     * Collapse single-child wrapper chains: if a parent has only one child and no text,
     * and the parent doesn't add semantic value (no meaningful id/class/role),
     * replace the parent with the child to reduce nesting depth.
     */
    private fun collapseSingleChildChains(element: Element?) {
        if (element == null) return
        
        var collapsedCount = 0
        
        // Process all children first (bottom-up)
        element.children().toList().forEach { child ->
            collapseSingleChildChains(child)
        }
        
        // Now collapse chains in this subtree
        val childrenToProcess = element.children().toList()
        for (child in childrenToProcess) {
            var node = child
            while (node.children().size == 1 && 
                   node.ownText().isBlank() &&
                   !hasMeaningfulAttributes(node)) {
                val onlyChild = node.children().first() ?: break
                val parent = node.parent() ?: break
                
                // Replace node with its only child
                val index = node.siblingIndex()
                node.remove()
                parent.insertChildren(index, onlyChild)
                collapsedCount++
                node = onlyChild
            }
        }
        
        if (collapsedCount > 0) {
            logger.debug("Collapsed {} single-child wrapper chains", collapsedCount)
        }
    }

    /**
     * Check if an element has meaningful attributes that should prevent it from being collapsed.
     */
    private fun hasMeaningfulAttributes(element: Element): Boolean {
        // Keep elements with meaningful IDs, specific classes, or semantic roles
        val id = element.attr("id")
        val classes = element.classNames()
        val role = element.attr("role")
        
        return id.isNotBlank() || 
               classes.isNotEmpty() ||
               role.isNotBlank() ||
               element.attr("colspan").isNotBlank() ||
               element.attr("rowspan").isNotBlank()
    }
}