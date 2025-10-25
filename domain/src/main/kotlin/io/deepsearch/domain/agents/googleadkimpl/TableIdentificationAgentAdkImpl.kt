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
        .description("List of CSS selectors for table roots")
        .properties(
            mapOf(
                "tables" to Schema.builder()
                    .type("ARRAY")
                    .description("Array of CSS selector strings pointing to table roots and captions")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                mapOf(
                                    "cssSelector" to Schema.builder().type("STRING").description("The CSS selector to the table.")
                                        .build(),
                                    "auxiliaryInfo" to Schema.builder().type("STRING")
                                        .description("The auxiliary info for the table.").build()
                                )
                            )
                            .required(listOf("cssSelector", "auxiliaryInfo"))
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("tables"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("tableIdentificationAgent")
        description("Identify tables in webpage using screenshot and cleaned HTML, return CSS selectors to their root containers")
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
            Your task is to identify all tables in the provided webpage and generate CSS selectors to their root containers.

            Inputs:
            - A screenshot of the full webpage
            - Cleaned HTML structure of the webpage

            Instructions:
            - Analyze both the screenshot and HTML to identify every table. A "table" is any data presented in a structured, grid-like format (rows and columns).
            - Look for both standard HTML table elements (<table>, <tr>, <td>, <th>) and CSS-based table layouts (divs with table-like styling).
            - For every table you find, create a CSS selector that targets the smallest ROOT CONTAINER element that wraps the entire table.
            - The CSS selectors should be as simplistic and direct as possible. It should contain no more than the bare minimal to uniquely identify the table.
            - Prefer using IDs when available (e.g., "#tableId"), then classes (e.g., ".table-class"), then element type with nth-child if needed (e.g., "table:nth-child(2)").
            - Additionally, extract auxiliaryInfo using surrounding text such as table headers and captions to provide extra information for understanding the table.

            Expected output shape:
            {
                "tables": [
                    {
                        "cssSelector": "string",
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
        val tables: List<TableIdentification>
    )

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

        logger.debug("Table identification found {} tables", response.tables.size)

        return TableIdentificationOutput(tables = response.tables)
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