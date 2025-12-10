package io.deepsearch.domain.agents.googlegenaiimpl

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
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TableIdentificationAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val cssSelectorConstructionService: ICssSelectorConstructionService
) : ITableIdentificationAgent {

    companion object {
        /** CSS selector for media elements (images and icons) */
        private const val MEDIA_ELEMENTS_SELECTOR = "img, svg, " +
            // Font Awesome
            "i.fa, i.fas, i.far, i.fab, i.fal, i.fad, i[class*=\"fa-\"], " +
            // Bootstrap Icons
            "i.bi, i[class*=\"bi-\"], " +
            // Material Design Icons
            "i.mdi, i[class*=\"mdi-\"], " +
            // Google Material Icons & Symbols
            ".material-icons, .material-symbols-outlined, .material-symbols-rounded, " +
            // Other icon libraries
            ".glyphicon, ion-icon, i[class*=\"icon\"], span[class*=\"icon\"], " +
            "[class*=\"icon-\"], [class*=\"-icon\"], [data-icon], [role=\"img\"]:not(img)"
    }

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

    private val systemInstruction = """
        Your task is to identify table structures in the provided webpage and return the stable identifiers of their root containers.

        Inputs:
        - Cleaned HTML structure of the webpage, all possible table containers are injected with data-ds-id attribute 

        Instructions:
        - Analyze the HTML to identify tables or grid-like structures in the webpage
        - Always target the root containers instead of individual rows and columns, a typical webpage should have a limited number of tables/grids
        - Modern websites may design tables purely using <div> styling or structure, you need to identify them based on semantic meaning
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
        // Step 1: Get webpage HTML and bounding boxes from snapshot
        val originalHtml = input.pageSnapshot.html
        val boundingBoxes = input.pageSnapshot.boundingBoxes

        logger.debug("Table identification for HTML ({} bytes)", originalHtml.length)
        logger.debug("Got {} bounding boxes for table identification", boundingBoxes.size)

        // Step 2: Inject stable identifiers into jsoup copy
        val htmlWithIds = injectStableIdentifiers(originalHtml, "ds-table")

        // Programmatic extraction of semantic tables to reduce LLM input
        val (programmaticTables, reducedHtml) = extractSemanticTables(htmlWithIds)
        logger.debug("Programmatically extracted {} semantic tables", programmaticTables.size)

        // Step 3: Clean HTML (after identifier injection)
        val cleanedHtml = cleanHtml(reducedHtml)

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)
        
        if (cleanedHtml.isEmpty()) {
            return TableIdentificationOutput(
                tables = emptyList(),
                tokenUsage = tokenUsage
            )
        }

        // Step 4: Pass to LLM
        
        val response = retryLlmCall<TableIdentificationResponse>(this::class.simpleName!!) {
            val result = client.models.generateContent(
                modelId,
                listOf(Content.fromParts(Part.fromText(cleanedHtml))),
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

        val allTableResults = programmaticTables + response.tables
        logger.debug(
            "Total table identification found {} table IDs ({} programmatic, {} LLM)",
            allTableResults.size, programmaticTables.size, response.tables.size
        )

        // Step 5: Reconstruct CSS selectors for all tables using batch method (single HTML parse)
        data class ResolvedTable(
            val llmResult: LlmTableResult,
            val cssSelector: String
        )

        val identifiers = allTableResults.map { it.id }
        val cssSelectorsMap = cssSelectorConstructionService.constructCssSelectorsFromIdentifiers(
            identifiers = identifiers,
            htmlWithIdentifiers = htmlWithIds
        )

        val resolvedTables = allTableResults.mapNotNull { llmResult ->
            val cssSelector = cssSelectorsMap[llmResult.id]

            if (cssSelector == null) {
                logger.warn(
                    "Skipping table with ID '{}' - could not construct valid CSS selector",
                    llmResult.id
                )
                return@mapNotNull null
            }

            logger.debug("Constructed CSS selector '{}' for identifier '{}'", cssSelector, llmResult.id)
            ResolvedTable(llmResult, cssSelector)
        }
        
        // Step 6: Batch inject all identifiers in a single CDP call
        if (resolvedTables.isNotEmpty()) {
            val injections = resolvedTables.map { resolved ->
                IBrowserPage.AttributeInjection(
                    cssSelector = resolved.cssSelector,
                    attributeName = "data-ds-id",
                    attributeValue = resolved.llmResult.id
                )
            }
            logger.debug("Batch injecting {} table identifiers", injections.size)
            input.webpage.injectAttributesByCssSelectors(injections)
        }
        
        // Pre-parse HTML once for media detection
        val docForMediaDetection = Jsoup.parse(htmlWithIds)

        // Convert resolved tables to TableIdentifications
        val tableIdentifications = resolvedTables.map { resolved ->
            val containsMedia = detectMediaInTable(resolved.llmResult.id, docForMediaDetection)
            TableIdentification(
                cssSelector = "[data-ds-id=\"${resolved.llmResult.id}\"]",
                auxiliaryInfo = resolved.llmResult.auxiliaryInfo,
                containsMedia = containsMedia
            )
        }

        logger.debug(
            "Table identification produced {} table identifications",
            tableIdentifications.size
        )

        return TableIdentificationOutput(
            tables = tableIdentifications,
            tokenUsage = tokenUsage
        )
    }

    /**
     * Detect if a table contains any media elements (icons or images).
     * Uses tag-based detection (img, svg, icon classes) so this works
     * without needing media extraction to inject IDs first.
     * Tables with media cannot be interpreted early - they must wait for media interpretation.
     *
     * @param tableId The data-ds-id of the table to check
     * @param doc Pre-parsed Document to avoid repeated HTML parsing
     */
    private fun detectMediaInTable(
        tableId: String,
        doc: Document
    ): Boolean {
        val tableElement = doc.select("[data-ds-id=\"$tableId\"]").firstOrNull()
            ?: return false

        // Check if any media element (by tag/class) exists within this table
        val mediaElements = tableElement.select(MEDIA_ELEMENTS_SELECTOR)
        val hasMedia = mediaElements.isNotEmpty()
        
        if (hasMedia) {
            logger.debug("Table '{}' contains {} media element(s)", tableId, mediaElements.size)
        }
        
        return hasMedia
    }

    private fun injectStableIdentifiers(cleanedHtml: String, idPrefix: String): String {
        val doc: Document = Jsoup.parse(cleanedHtml)

        var idCounter = 0
        doc.select("table, div, section, article, main, aside, ul, ol, dl").forEach { element ->
            element.attr("data-ds-id", "$idPrefix-${idCounter++}")
        }

        return doc.outerHtml()
    }

    private fun extractSemanticTables(htmlWithIds: String): Pair<List<LlmTableResult>, String> {
        val doc = Jsoup.parse(htmlWithIds)
        val tables = mutableListOf<LlmTableResult>()

        val tableElements = doc.select("table[data-ds-id]")

        tableElements.forEach { element ->
            val id = element.attr("data-ds-id")
            val caption = element.select("caption").text()
            val summary = element.attr("summary")
            val auxiliaryInfo = when {
                caption.isNotBlank() -> caption
                summary.isNotBlank() -> summary
                else -> ""
            }

            tables.add(LlmTableResult(id, auxiliaryInfo))
            element.remove()
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
                        attr.key == "data-ds-id" ||
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
}


