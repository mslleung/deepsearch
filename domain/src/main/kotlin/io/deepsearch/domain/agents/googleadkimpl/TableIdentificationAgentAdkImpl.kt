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

            Instructions:
            - Analyze the HTML to identify every table-like structure in the webpage
            - Always target the table root containers instead of individual rows and columns
            - In the event of nested tables/grids, target the outermost wrapping parent only
            - Modern websites may design tables purely using <div> styling or structure, you need to identify them based on semantic meaning
            - For every table you find, return the data-ds-id attribute value (e.g., "ds-table-5") pointing to the root container.
            - Additionally, extract auxiliaryInfo using surrounding text such as table headers and captions to provide extra information for understanding the table.

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
        logger.debug("Table identification for HTML ({} bytes)", input.html.length)

        val cleanedHtml = cleanHtml(input.html)

        if (cleanedHtml.isEmpty()) {
            return TableIdentificationOutput(tables = emptyList())
        }

        // Inject stable identifiers for LLM processing
        val cleanedHtmlWithIds = injectStableIdentifiers(cleanedHtml, "ds-table")

        // Programmatic extraction of semantic tables to reduce LLM input
        val (programmaticTables, reducedHtml) = extractSemanticTables(cleanedHtmlWithIds)
        logger.debug("Programmatically extracted {} semantic tables", programmaticTables.size)

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
        logger.debug("Total table identification found {} table IDs ({} programmatic, {} LLM)", 
            allTableResults.size, programmaticTables.size, response.tables.size)

        // Convert IDs to HTML snippets, then to CSS selectors that work on original HTML
        val cleanedDocWithIds = Jsoup.parse(cleanedHtmlWithIds)
        
        val tableIdentifications = allTableResults.flatMap { llmResult ->
            val tempSelector = "[data-ds-id=\"${llmResult.id}\"]"
            val element = cleanedDocWithIds.selectFirst(tempSelector)
            
            if (element == null) {
                logger.warn("Skipping table with ID '{}' - element not found in cleaned HTML", llmResult.id)
                emptyList()
            } else {
                // Remove temporary IDs from element and all its descendants (they don't exist in original HTML)
                element.removeAttr("data-ds-id")
                element.select("[data-ds-id]").forEach { it.removeAttr("data-ds-id") }
                val htmlSnippet = element.outerHtml()
                
                // Convert snippet to CSS selectors that work on original HTML
                // Use cleanedHtml (without IDs) for proper CSS selector construction
                val cssSelectors = cssSelectorConstructionService.constructCssSelectorsFromSnippet(
                    htmlSnippet, cleanedHtml, input.html
                )

                if (cssSelectors.isEmpty()) {
                    logger.warn(
                        "Skipping table with ID '{}' - could not construct valid CSS selector from snippet",
                        llmResult.id
                    )
                    emptyList()
                } else {
                    logger.debug("CSS selectors: {}", cssSelectors)
                    cssSelectors.map { cssSelector ->
                        TableIdentification(
                            cssSelector = cssSelector,
                            auxiliaryInfo = llmResult.auxiliaryInfo
                        )
                    }
                }
            }
        }

        logger.debug(
            "Table identification produced {} table identifications",
            tableIdentifications.size
        )

        return TableIdentificationOutput(tables = tableIdentifications)
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
}