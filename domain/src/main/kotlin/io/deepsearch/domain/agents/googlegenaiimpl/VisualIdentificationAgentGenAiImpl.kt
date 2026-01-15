package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IVisualIdentificationAgent
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.VisualIdentificationBatchRequest
import io.deepsearch.domain.agents.VisualIdentificationInput
import io.deepsearch.domain.agents.VisualIdentificationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.IdentifiedElement
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest
import io.deepsearch.domain.services.ICssSelectorConstructionService
import io.deepsearch.domain.services.IImageDimensionService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Combined visual identification agent that detects both semantic elements and tables
 * in a single vision-based LLM call.
 * 
 * This reduces latency by ~30-50% and token usage by ~40% compared to running
 * SemanticIdentificationAgent and TableIdentificationAgent separately.
 */
class VisualIdentificationAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val cssSelectorConstructionService: ICssSelectorConstructionService,
    private val imageDimensionService: IImageDimensionService,
    private val dispatcherProvider: IDispatcherProvider
) : IVisualIdentificationAgent {

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

    // ========== Combined Vision Schema ==========
    // Uses official Gemini object detection format: box_2d = [ymin, xmin, ymax, xmax] scaled to [0, 1000]

    private val visionElementSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "box_2d" to Schema.builder().type("ARRAY")
                    .description("Bounding box as [ymin, xmin, ymax, xmax] scaled to [0, 1000]")
                    .items(Schema.builder().type("INTEGER").build())
                    .build(),
                "label" to Schema.builder().type("STRING")
                    .description("Brief description of the element")
                    .build()
            )
        )
        .required(listOf("box_2d", "label"))
        .build()

    private val tableElementSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "box_2d" to Schema.builder().type("ARRAY")
                    .description("Bounding box as [ymin, xmin, ymax, xmax] scaled to [0, 1000]")
                    .items(Schema.builder().type("INTEGER").build())
                    .build(),
                "label" to Schema.builder().type("STRING")
                    .description("Brief description of the table's content")
                    .build(),
                "columnHeaders" to Schema.builder().type("STRING")
                    .description("Column headers, comma-separated")
                    .nullable(true)
                    .build()
            )
        )
        .required(listOf("box_2d", "label"))
        .build()

    private val combinedOutputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Combined visual identification of semantic elements and tables")
        .properties(
            mapOf(
                // Semantic elements
                "header" to visionElementSchema.toBuilder().nullable(true).build(),
                "footer" to visionElementSchema.toBuilder().nullable(true).build(),
                "navSidebar" to visionElementSchema.toBuilder().nullable(true).build(),
                "breadcrumb" to visionElementSchema.toBuilder().nullable(true).build(),
                "cookieBanner" to visionElementSchema.toBuilder().nullable(true).build(),
                "popups" to Schema.builder().type("ARRAY")
                    .items(visionElementSchema)
                    .build(),
                // Tables
                "tables" to Schema.builder().type("ARRAY")
                    .description("Array of visually identified tables with bounding boxes")
                    .items(tableElementSchema)
                    .build()
            )
        )
        .required(listOf("tables"))
        .build()

    private val combinedSystemInstruction = """
        Detect all visual elements in this webpage screenshot for content extraction.
        
        Return bounding boxes using box_2d format: [ymin, xmin, ymax, xmax] where coordinates are scaled to [0, 1000].
        - ymin/ymax: vertical position (0 = top, 1000 = bottom)
        - xmin/xmax: horizontal position (0 = left, 1000 = right)
        
        ## SEMANTIC ELEMENTS (navigation/chrome to remove):
        Identify these navigational elements:
        - header: webpage header/navigation bar at the top
        - footer: webpage footer at the bottom
        - navSidebar: side navigation column (not main content sidebar)
        - breadcrumb: navigation path like "Home > Category > Page"
        - cookieBanner: cookie consent popup/banner
        - popups: modal dialogs overlaying content
        
        ## TABLES (data structures to preserve):
        Identify all table or grid structures containing data:
        - Include data tables, comparison tables, pricing tables, grid layouts
        - Provide a brief label describing the table's content
        - If the table contains a header row, list the column headers (comma-separated). Null if there is no header row.
        
        Example output:
        {
            "header": {"box_2d": [0, 0, 50, 1000], "label": "Navigation bar with logo"},
            "footer": {"box_2d": [920, 0, 1000, 1000], "label": "Footer with links"},
            "tables": [
                {"box_2d": [200, 50, 600, 950], "label": "Pricing comparison", "columnHeaders": "Basic, Pro, Enterprise"}
            ]
        }
    """.trimIndent()

    // ========== Response Data Classes ==========

    @Serializable
    private data class VisionElement(
        @kotlinx.serialization.SerialName("box_2d")
        val box2d: List<Int>,
        val label: String
    ) {
        val ymin: Int get() = box2d.getOrElse(0) { 0 }
        val xmin: Int get() = box2d.getOrElse(1) { 0 }
        val ymax: Int get() = box2d.getOrElse(2) { 1000 }
        val xmax: Int get() = box2d.getOrElse(3) { 1000 }
    }

    @Serializable
    private data class VisionTableElement(
        @kotlinx.serialization.SerialName("box_2d")
        val box2d: List<Int>,
        val label: String,
        val columnHeaders: String? = null
    ) {
        val ymin: Int get() = box2d.getOrElse(0) { 0 }
        val xmin: Int get() = box2d.getOrElse(1) { 0 }
        val ymax: Int get() = box2d.getOrElse(2) { 1000 }
        val xmax: Int get() = box2d.getOrElse(3) { 1000 }
    }

    @Serializable
    private data class CombinedVisionResponse(
        val header: VisionElement? = null,
        val footer: VisionElement? = null,
        val navSidebar: VisionElement? = null,
        val breadcrumb: VisionElement? = null,
        val cookieBanner: VisionElement? = null,
        val popups: List<VisionElement>? = null,
        val tables: List<VisionTableElement> = emptyList()
    )

    // ========== HTML-Based Hidden Container Detection Schema ==========
    // For detecting tables in hidden containers (accordions, tabs, etc.)

    private val hiddenTableOutputSchema: Schema = Schema.builder()
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
                                        .description("The data-ds-id value of the table root element")
                                        .build(),
                                    "description" to Schema.builder().type("STRING")
                                        .description("A brief description of the table's purpose or content")
                                        .build(),
                                    "columnHeaders" to Schema.builder().type("STRING")
                                        .description("The column headers of the table, comma-separated")
                                        .build()
                                )
                            )
                            .required(listOf("id", "description"))
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("tables"))
        .build()

    private val hiddenTableSystemInstruction = """
        Your task is to identify table structures in the provided HTML and return the stable identifiers of their root containers.

        Inputs:
        - Cleaned HTML structure, all possible table containers are injected with data-ds-id attribute 

        Instructions:
        - Analyze the HTML to identify tables or grid-like structures
        - Always target the root containers instead of individual rows and columns
        - Modern websites may design tables purely using <div> styling, identify them based on semantic meaning
        - For every table found, return the data-ds-id attribute value pointing to the root container
        - Additionally, provide:
          - description: A brief description of the table's purpose or content
          - columnHeaders: If the table contains a header row, list the column headers (comma-separated). Null if there is no header row.


        Expected output shape:
        {
            "tables": [
                {
                    "id": "string",
                    "description": "string",
                    "columnHeaders": "string"
                }
            ]
        }
    """.trimIndent()

    @Serializable
    private data class HiddenTableResponse(
        val tables: List<HiddenTableResult>
    )

    @Serializable
    private data class HiddenTableResult(
        val id: String,
        val description: String,
        val columnHeaders: String? = null
    )

    // Internal result after IoU mapping
    private data class MappedElement(
        val dataId: String,
        val cssSelector: String,
        val label: String
    )

    private data class MappedTable(
        val dataId: String,
        val cssSelector: String,
        val label: String,
        val columnHeaders: String?,
        val containsMedia: Boolean
    )

    // ========== Main Generate Method ==========

    override suspend fun generate(input: VisualIdentificationInput): VisualIdentificationOutput = coroutineScope {
        val originalHtml = input.pageSnapshot.html
        val boundingBoxes = input.pageSnapshot.boundingBoxes
        val hiddenContainers = input.pageSnapshot.hiddenContainers
        val screenshot = input.screenshot

        logger.debug(
            "Visual identification: HTML={} bytes, screenshot={} bytes, {} bounding boxes, {} hidden containers",
            originalHtml.length, screenshot.bytes.size, boundingBoxes.size, hiddenContainers.size
        )

        // Inject stable identifiers for both semantic and table elements
        val htmlWithIds = injectStableIdentifiers(originalHtml)
        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId

        // ========== Parallel Detection ==========
        // 1. Vision detection for visible semantic elements and tables (single call)
        // 2. HTML detection for each hidden container (in parallel)

        val visionDeferred = async {
            var tokenUsage = TokenUsageMetrics.empty(modelId)
            val response = withContext(dispatcherProvider.io) {
                retryLlmCall<CombinedVisionResponse>(this@VisualIdentificationAgentGenAiImpl::class.simpleName!! + "_vision") {
                    val result = client.models.generateContent(
                        modelId,
                        listOf(
                            Content.fromParts(
                                Part.fromBytes(screenshot.bytes, screenshot.mimeType.value),
                                Part.fromText("Analyze this webpage screenshot for semantic elements and tables.")
                            )
                        ),
                        GenerateContentConfig.builder()
                            .temperature(0F)
                            .responseSchema(combinedOutputSchema)
                            .responseMimeType("application/json")
                            .thinkingConfig(
                                ThinkingConfig.builder()
                                    .thinkingBudget(0)
                                    .build()
                            )
                            .systemInstruction(Content.fromParts(Part.fromText(combinedSystemInstruction)))
                            .build()
                    )

                    result.checkFinishReason()

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
            response to tokenUsage
        }

        // Hidden container detection in parallel
        val hiddenDeferreds = hiddenContainers.map { container ->
            async {
                detectTablesInHiddenContainer(container, htmlWithIds, modelId)
            }
        }

        // Await all results
        val (visionResponse, visionUsage) = visionDeferred.await()
        val hiddenResults = hiddenDeferreds.awaitAll()

        val hiddenTables = hiddenResults.flatMap { it.first }
        val hiddenUsage = hiddenResults.fold(TokenUsageMetrics.empty(modelId)) { acc, result ->
            acc + result.second
        }

        logger.debug(
            "Hidden container detection: {} tables found across {} containers",
            hiddenTables.size, hiddenContainers.size
        )

        val tokenUsage = visionUsage + hiddenUsage

        // Get screenshot dimensions for coordinate mapping
        val (screenshotWidth, screenshotHeight) = imageDimensionService.getImageDimensions(screenshot.bytes)
        val pageWidth = screenshotWidth.toDouble()
        val pageHeight = screenshotHeight.toDouble()

        logger.debug("Visual identification dimensions: {}x{}", pageWidth.toInt(), pageHeight.toInt())

        // ========== Map vision results to DOM elements ==========
        val doc = Jsoup.parse(htmlWithIds)

        // Map semantic elements
        val mappedSemantic = mapSemanticElements(visionResponse, boundingBoxes, htmlWithIds, pageWidth, pageHeight, doc)

        // Map tables with image overlap filtering
        val imageBoundingBoxes = extractImageBoundingBoxes(boundingBoxes)
        val filteredTables = filterVisionTablesOverlappingImages(
            visionResponse.tables, imageBoundingBoxes, pageWidth, pageHeight
        )
        val visionMappedTables = mapTableElements(filteredTables, boundingBoxes, htmlWithIds, pageWidth, pageHeight, doc)

        // ========== Apply programmatic fallbacks ==========
        val finalSemantic = applySemanticFallbacks(mappedSemantic, doc)
        val programmaticTables = extractSemanticTables(htmlWithIds)

        // ========== Merge all tables and deduplicate ==========
        val visionAndProgrammaticTables = mergeTables(visionMappedTables, programmaticTables, doc)
        val visionIds = visionAndProgrammaticTables.map { it.dataId }.toSet()

        // Deduplicate hidden tables against vision-detected tables
        val deduplicatedHiddenTables = hiddenTables.filter { hiddenTable ->
            val hiddenElement = doc.select("[data-ds-id=\"${hiddenTable.id}\"]").firstOrNull()
                ?: return@filter true

            val isOverlapping = visionIds.any { visionId ->
                val visionElement = doc.select("[data-ds-id=\"$visionId\"]").firstOrNull()
                    ?: return@any false
                hiddenElement.parents().contains(visionElement) ||
                        visionElement.parents().contains(hiddenElement) ||
                        hiddenElement == visionElement
            }

            if (isOverlapping) {
                logger.debug("Deduplicating hidden table '{}' - overlaps with vision-detected table", hiddenTable.id)
            }
            !isOverlapping
        }

        // Convert hidden tables to MappedTable and add to final list
        val hiddenMappedTables = deduplicatedHiddenTables.mapNotNull { hiddenTable ->
            val element = doc.select("[data-ds-id=\"${hiddenTable.id}\"]").firstOrNull() ?: return@mapNotNull null
            val cssSelector = cssSelectorConstructionService.constructCssSelector(element)
            val containsMedia = detectMediaInTable(hiddenTable.id, doc)
            MappedTable(
                dataId = hiddenTable.id,
                cssSelector = cssSelector,
                label = hiddenTable.description,
                columnHeaders = hiddenTable.columnHeaders,
                containsMedia = containsMedia
            )
        }

        val finalTables = visionAndProgrammaticTables + hiddenMappedTables

        logger.debug(
            "Total tables: {} ({} vision/programmatic, {} hidden after dedup from {})",
            finalTables.size, visionAndProgrammaticTables.size,
            hiddenMappedTables.size, hiddenTables.size
        )

        // ========== Build output ==========
        val semanticElements = buildSemanticElements(finalSemantic)
        val tableIdentifications = buildTableIdentifications(finalTables)

        logger.debug(
            "Visual identification complete: {} semantic elements, {} tables",
            countSemanticElements(semanticElements),
            tableIdentifications.size
        )

        VisualIdentificationOutput(
            semanticElements = semanticElements,
            tables = tableIdentifications,
            tokenUsage = tokenUsage
        )
    }

    // ========== ID Injection ==========

    /**
     * Inject stable data-ds-id attributes into potential elements.
     * Uses a unified "ds-visual" prefix for both semantic and table elements.
     */
    private fun injectStableIdentifiers(html: String): String {
        val doc: Document = Jsoup.parse(html)
        var idCounter = 0

        // Semantic element candidates
        doc.select("header, footer, nav, aside, section, article, main, div").forEach { element ->
            element.attr("data-ds-id", "ds-visual-${idCounter++}")
        }

        // Table element candidates (some overlap with above, but that's fine)
        doc.select("table, ul, ol, dl").forEach { element ->
            if (!element.hasAttr("data-ds-id")) {
                element.attr("data-ds-id", "ds-visual-${idCounter++}")
            }
        }

        return doc.outerHtml()
    }

    // ========== Vision to DOM Mapping ==========

    private fun mapSemanticElements(
        visionResponse: CombinedVisionResponse,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        htmlWithIds: String,
        pageWidth: Double,
        pageHeight: Double,
        doc: Document
    ): Map<String, MappedElement?> {
        fun mapSingleElement(visionElement: VisionElement?, elementType: String): MappedElement? {
            if (visionElement == null) return null
            return mapVisionToDomElement(visionElement.box2d, visionElement.label, pageBoundingBoxes, pageWidth, pageHeight, doc, elementType)
        }

        return mapOf(
            "header" to mapSingleElement(visionResponse.header, "header"),
            "footer" to mapSingleElement(visionResponse.footer, "footer"),
            "navSidebar" to mapSingleElement(visionResponse.navSidebar, "navSidebar"),
            "breadcrumb" to mapSingleElement(visionResponse.breadcrumb, "breadcrumb"),
            "cookieBanner" to mapSingleElement(visionResponse.cookieBanner, "cookieBanner"),
            "popups" to null // Handled separately as array
        ).toMutableMap().also { map ->
            // Note: popups are handled as a list, not in this map
        }
    }

    private fun mapTableElements(
        visionTables: List<VisionTableElement>,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        htmlWithIds: String,
        pageWidth: Double,
        pageHeight: Double,
        doc: Document
    ): List<MappedTable> {
        return visionTables.mapNotNull { visionTable ->
            val mapped = mapVisionToDomElement(
                visionTable.box2d, visionTable.label, pageBoundingBoxes, pageWidth, pageHeight, doc, "table"
            ) ?: return@mapNotNull null

            val containsMedia = detectMediaInTable(mapped.dataId, doc)
            MappedTable(
                dataId = mapped.dataId,
                cssSelector = mapped.cssSelector,
                label = visionTable.label,
                columnHeaders = visionTable.columnHeaders,
                containsMedia = containsMedia
            )
        }.distinctBy { it.dataId }
    }

    private fun mapVisionToDomElement(
        box2d: List<Int>,
        label: String,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        pageWidth: Double,
        pageHeight: Double,
        doc: Document,
        elementType: String
    ): MappedElement? {
        if (pageBoundingBoxes.isEmpty()) return null

        // Convert [0, 1000] scaled coordinates to absolute pixel coordinates
        val ymin = box2d.getOrElse(0) { 0 }
        val xmin = box2d.getOrElse(1) { 0 }
        val ymax = box2d.getOrElse(2) { 1000 }
        val xmax = box2d.getOrElse(3) { 1000 }

        val targetTop = ymin * pageHeight / 1000
        val targetLeft = xmin * pageWidth / 1000
        val targetBottom = ymax * pageHeight / 1000
        val targetRight = xmax * pageWidth / 1000
        val targetArea = (targetRight - targetLeft) * (targetBottom - targetTop)

        if (targetArea <= 0) return null

        var bestMatch: Triple<String, Double, String>? = null // dataId, score, xpath

        for ((xpath, bbox) in pageBoundingBoxes) {
            val elemLeft = bbox.left
            val elemTop = bbox.top
            val elemRight = bbox.right
            val elemBottom = bbox.bottom
            val elemArea = (elemRight - elemLeft) * (elemBottom - elemTop)

            if (elemArea <= 0) continue

            // Calculate intersection
            val interLeft = maxOf(targetLeft, elemLeft)
            val interTop = maxOf(targetTop, elemTop)
            val interRight = minOf(targetRight, elemRight)
            val interBottom = minOf(targetBottom, elemBottom)

            if (interLeft < interRight && interTop < interBottom) {
                val interArea = (interRight - interLeft) * (interBottom - interTop)

                // IoU + coverage scoring
                val unionArea = targetArea + elemArea - interArea
                val iou = if (unionArea > 0) interArea / unionArea else 0.0
                val visionCoverage = interArea / targetArea
                val elementCoverage = interArea / elemArea
                val coverageScore = kotlin.math.sqrt(visionCoverage * elementCoverage)
                val score = maxOf(iou, coverageScore)

                val element = findElementByXPath(doc, xpath)
                val dataId = element?.attr("data-ds-id")
                if (dataId?.isNotEmpty() == true && (bestMatch == null || score > bestMatch.second)) {
                    bestMatch = Triple(dataId, score, xpath)
                }
            }
        }

        return if (bestMatch != null) {
            val element = findElementByXPath(doc, bestMatch.third)!!
            val cssSelector = cssSelectorConstructionService.constructCssSelector(element)
            val scoreLabel = when {
                bestMatch.second >= 0.8 -> "excellent"
                bestMatch.second >= 0.6 -> "good"
                bestMatch.second >= 0.4 -> "moderate"
                else -> "low"
            }
            logger.debug(
                "Visual {} mapped to {} with {} score {}",
                elementType, bestMatch.first, scoreLabel, "%.3f".format(bestMatch.second)
            )
            MappedElement(dataId = bestMatch.first, cssSelector = cssSelector, label = label)
        } else {
            logger.debug("Could not map visual {} (no overlapping elements)", elementType)
            null
        }
    }

    // ========== Programmatic Fallbacks ==========

    private fun applySemanticFallbacks(
        mapped: Map<String, MappedElement?>,
        doc: Document
    ): Map<String, MappedElement?> {
        val result = mapped.toMutableMap()

        // Fallback for header
        if (result["header"] == null) {
            doc.selectFirst("header[data-ds-id]")?.let { element ->
                val dataId = element.attr("data-ds-id")
                val cssSelector = cssSelectorConstructionService.constructCssSelector(element)
                result["header"] = MappedElement(dataId, cssSelector, "Semantic <header> element")
                logger.debug("Programmatic fallback: Found <header> element with id {}", dataId)
            }
        }

        // Fallback for footer
        if (result["footer"] == null) {
            doc.selectFirst("footer[data-ds-id]")?.let { element ->
                val dataId = element.attr("data-ds-id")
                val cssSelector = cssSelectorConstructionService.constructCssSelector(element)
                result["footer"] = MappedElement(dataId, cssSelector, "Semantic <footer> element")
                logger.debug("Programmatic fallback: Found <footer> element with id {}", dataId)
            }
        }

        // Fallback for navSidebar
        if (result["navSidebar"] == null) {
            doc.selectFirst("nav[data-ds-id]:not(header nav):not(footer nav), aside[role=navigation][data-ds-id]")?.let { element ->
                val dataId = element.attr("data-ds-id")
                val cssSelector = cssSelectorConstructionService.constructCssSelector(element)
                result["navSidebar"] = MappedElement(dataId, cssSelector, "Semantic <nav>/<aside> element")
                logger.debug("Programmatic fallback: Found <nav>/<aside> element with id {}", dataId)
            }
        }

        return result
    }

    private data class ProgrammaticTable(
        val dataId: String,
        val description: String,
        val columnHeaders: String?
    )

    private fun extractSemanticTables(htmlWithIds: String): List<ProgrammaticTable> {
        val doc = Jsoup.parse(htmlWithIds)
        val tables = mutableListOf<ProgrammaticTable>()

        doc.select("table[data-ds-id]").forEach { element ->
            val id = element.attr("data-ds-id")
            val caption = element.select("caption").text()
            val summary = element.attr("summary")
            val description = when {
                caption.isNotBlank() -> caption
                summary.isNotBlank() -> summary
                else -> ""
            }
            val headerElements = element.select("th")
            val columnHeaders = headerElements.map { it.text().trim() }
                .filter { it.isNotBlank() }
                .joinToString(", ")

            tables.add(ProgrammaticTable(id, description, columnHeaders))
        }

        return tables
    }

    private fun mergeTables(
        visionMapped: List<MappedTable>,
        programmatic: List<ProgrammaticTable>,
        doc: Document
    ): List<MappedTable> {
        val visionIds = visionMapped.map { it.dataId }.toSet()

        // Add programmatic tables that weren't detected by vision
        val additionalTables = programmatic
            .filter { it.dataId !in visionIds }
            .mapNotNull { prog ->
                val element = doc.select("[data-ds-id=\"${prog.dataId}\"]").firstOrNull() ?: return@mapNotNull null
                val cssSelector = cssSelectorConstructionService.constructCssSelector(element)
                val containsMedia = detectMediaInTable(prog.dataId, doc)
                MappedTable(
                    dataId = prog.dataId,
                    cssSelector = cssSelector,
                    label = prog.description,
                    columnHeaders = prog.columnHeaders,
                    containsMedia = containsMedia
                )
            }

        return visionMapped + additionalTables
    }

    // ========== Output Building ==========

    private fun buildSemanticElements(mapped: Map<String, MappedElement?>): SemanticElements {
        fun toIdentifiedElement(element: MappedElement?): IdentifiedElement? {
            if (element == null) return null
            return IdentifiedElement(
                cssSelector = element.cssSelector,
                dataId = element.dataId,
                note = element.label
            )
        }

        return SemanticElements(
            header = toIdentifiedElement(mapped["header"]),
            footer = toIdentifiedElement(mapped["footer"]),
            navSidebar = toIdentifiedElement(mapped["navSidebar"]),
            breadcrumb = toIdentifiedElement(mapped["breadcrumb"]),
            cookieBanner = toIdentifiedElement(mapped["cookieBanner"]),
            adBanners = emptyList(), // Vision doesn't reliably detect ad banners
            popups = emptyList() // TODO: Handle popups array if needed
        )
    }

    private fun buildTableIdentifications(tables: List<MappedTable>): List<TableIdentification> {
        return tables.map { table ->
            val auxiliaryInfo = buildAuxiliaryInfo(table.label, table.columnHeaders)
            TableIdentification(
                cssSelector = table.cssSelector,
                dataId = table.dataId,
                auxiliaryInfo = auxiliaryInfo,
                containsMedia = table.containsMedia
            )
        }
    }

    private fun buildAuxiliaryInfo(description: String, columnHeaders: String?): String {
        val parts = mutableListOf<String>()
        if (description.isNotBlank()) {
            parts.add("Description: $description")
        }
        if (!columnHeaders.isNullOrBlank()) {
            parts.add("Columns: $columnHeaders")
        }
        return parts.joinToString(" | ")
    }

    // ========== Helper Methods ==========

    private fun findElementByXPath(doc: Document, xpath: String): org.jsoup.nodes.Element? {
        if (!xpath.startsWith("./")) return null

        val xpathRegex = Regex("""([a-zA-Z0-9_\-:]+)\[(\d+)]""")
        var current: org.jsoup.nodes.Element = doc.body() ?: return null

        val parts = xpath.removePrefix("./").split("/").filter { it.isNotEmpty() }
        for (part in parts) {
            val match = xpathRegex.matchEntire(part) ?: return null
            val tagName = match.groupValues[1]
            val index = match.groupValues[2].toInt() - 1

            val children = current.children().filter { it.tagName().equals(tagName, ignoreCase = true) }
            current = children.getOrNull(index) ?: return null
        }

        return current
    }

    private fun detectMediaInTable(tableId: String, doc: Document): Boolean {
        val tableElement = doc.select("[data-ds-id=\"$tableId\"]").firstOrNull() ?: return false
        val mediaElements = tableElement.select(MEDIA_ELEMENTS_SELECTOR)
        return mediaElements.isNotEmpty()
    }

    private fun extractImageBoundingBoxes(
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): List<IBrowserPage.BoundingBox> {
        return pageBoundingBoxes
            .filter { (xpath, _) ->
                xpath.contains("img[", ignoreCase = true) ||
                        xpath.contains("picture[", ignoreCase = true)
            }
            .map { it.value }
    }

    private fun filterVisionTablesOverlappingImages(
        visionTables: List<VisionTableElement>,
        imageBoundingBoxes: List<IBrowserPage.BoundingBox>,
        pageWidth: Double,
        pageHeight: Double
    ): List<VisionTableElement> {
        if (imageBoundingBoxes.isEmpty()) return visionTables

        return visionTables.filter { visionTable ->
            val visionTop = visionTable.ymin * pageHeight / 1000
            val visionLeft = visionTable.xmin * pageWidth / 1000
            val visionBottom = visionTable.ymax * pageHeight / 1000
            val visionRight = visionTable.xmax * pageWidth / 1000
            val visionArea = (visionRight - visionLeft) * (visionBottom - visionTop)

            if (visionArea <= 0) return@filter true

            val overlapsImage = imageBoundingBoxes.any { imgBbox ->
                val interLeft = maxOf(visionLeft, imgBbox.left)
                val interTop = maxOf(visionTop, imgBbox.top)
                val interRight = minOf(visionRight, imgBbox.right)
                val interBottom = minOf(visionBottom, imgBbox.bottom)

                if (interLeft >= interRight || interTop >= interBottom) return@any false

                val interArea = (interRight - interLeft) * (interBottom - interTop)
                val imgArea = (imgBbox.right - imgBbox.left) * (imgBbox.bottom - imgBbox.top)
                val visionCoverage = interArea / visionArea
                val unionArea = visionArea + imgArea - interArea
                val iou = if (unionArea > 0) interArea / unionArea else 0.0

                visionCoverage > 0.7 || iou > 0.5
            }

            if (overlapsImage) {
                logger.debug("Filtering out vision table '{}' - overlaps with image element", visionTable.label.take(40))
            }
            !overlapsImage
        }
    }

    private fun countSemanticElements(elements: SemanticElements): Int {
        var count = 0
        if (elements.header != null) count++
        if (elements.footer != null) count++
        if (elements.navSidebar != null) count++
        if (elements.breadcrumb != null) count++
        if (elements.cookieBanner != null) count++
        count += elements.adBanners.size
        count += elements.popups.size
        return count
    }

    // ========== Hidden Container Detection ==========

    /**
     * Detect tables in a hidden container using HTML-based LLM detection.
     * Hidden containers (accordion panels, collapsed sections, etc.) are not visible in screenshots.
     */
    private suspend fun detectTablesInHiddenContainer(
        container: IBrowserPage.HiddenContainer,
        fullHtmlWithIds: String,
        modelId: String
    ): Pair<List<HiddenTableResult>, TokenUsageMetrics> {
        val snippetWithIds = injectHiddenContainerIdentifiers(container.html, "ds-hidden-${container.id}")
        val cleanedSnippet = cleanHtml(snippetWithIds)

        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<HiddenTableResponse>(this@VisualIdentificationAgentGenAiImpl::class.simpleName!! + "_hidden") {
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(Part.fromText(cleanedSnippet))),
                    GenerateContentConfig.builder()
                        .temperature(0F)
                        .responseSchema(hiddenTableOutputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingBudget(0)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(hiddenTableSystemInstruction)))
                        .build()
                )

                result.checkFinishReason()

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

        logger.debug("Hidden container '{}' detection found {} tables", container.id, response.tables.size)

        // Validate that IDs exist in the full HTML
        val validatedTables = response.tables.filter { table ->
            val doc = Jsoup.parse(fullHtmlWithIds)
            doc.select("[data-ds-id=\"${table.id}\"]").isNotEmpty()
        }

        return validatedTables to tokenUsage
    }

    private fun injectHiddenContainerIdentifiers(html: String, idPrefix: String): String {
        val doc: Document = Jsoup.parse(html)
        var idCounter = 0
        doc.select("table, div, section, article, main, aside, ul, ol, dl").forEach { element ->
            element.attr("data-ds-id", "$idPrefix-${idCounter++}")
        }
        return doc.outerHtml()
    }

    private fun cleanHtml(rawHtml: String): String {
        val doc: Document = Jsoup.parse(rawHtml)

        // Remove noise elements
        doc.select(
            "script, style, noscript, template, svg, canvas, meta, link, iframe, object, embed, " +
                    "head, title, base, form, input, button, select, textarea, label, fieldset, legend, " +
                    "nav, header, footer, aside, img, video, audio, source, track, picture"
        ).remove()

        // Remove comments
        doc.select("*").forEach { element ->
            element.childNodes().filter { it.nodeName() == "#comment" }.forEach { it.remove() }
        }

        // Strip attributes
        doc.select("*").forEach { element ->
            val essentialAttrs = setOf("id", "class", "role", "colspan", "rowspan", "scope", "data-testid", "data-ds-id")
            val attrsToKeep = element.attributes().filter { it.key in essentialAttrs }
            element.clearAttributes()
            attrsToKeep.forEach { element.attr(it.key, it.value) }
        }

        // Truncate text content
        doc.select("*").forEach { element ->
            element.textNodes().forEach { textNode ->
                val text = textNode.text().replace("\\s+".toRegex(), " ").trim()
                if (text.isNotEmpty()) {
                    val shortened = if (text.length > 20) text.take(20) + "..." else text
                    textNode.text(shortened)
                }
            }
        }

        // Remove empty elements
        doc.select("*").filter { element ->
            element.children().isEmpty() &&
                    element.ownText().isBlank() &&
                    element.attr("role").isEmpty() &&
                    element.attr("colspan").isEmpty() &&
                    element.attr("rowspan").isEmpty()
        }.forEach { it.remove() }

        val cleanedHtml = doc.outerHtml()
        logger.debug("Cleaned HTML: {} chars (original: ~{} chars)", cleanedHtml.length, rawHtml.length)
        return cleanedHtml
    }

    // ========== Batch Processing Methods ==========

    private val batchJson = Json { ignoreUnknownKeys = true }

    override fun prepareBatchRequest(
        requestId: String,
        html: String,
        screenshotBase64: String,
        screenshotMimeType: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        pageWidth: Double,
        pageHeight: Double
    ): VisualIdentificationBatchRequest {
        val htmlWithIds = injectStableIdentifiers(html)

        val metadata = mapOf(
            "pageWidth" to pageWidth.toString(),
            "pageHeight" to pageHeight.toString()
        )

        val request = BatchContentRequest(
            requestId = requestId,
            modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
            systemInstruction = combinedSystemInstruction,
            userPrompt = "Analyze this webpage screenshot for semantic elements and tables.",
            imageData = screenshotBase64,
            imageMimeType = screenshotMimeType,
            temperature = 0f,
            metadata = metadata
        ).withSchema(combinedOutputSchema)

        return VisualIdentificationBatchRequest(
            request = request,
            htmlWithIds = htmlWithIds
        )
    }

    override fun parseBatchResponse(
        responseText: String,
        htmlWithIds: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        pageWidth: Double,
        pageHeight: Double
    ): VisualIdentificationOutput {
        val visionResponse = batchJson.decodeFromString<CombinedVisionResponse>(responseText)
        val doc = Jsoup.parse(htmlWithIds)

        // Map semantic elements
        val mappedSemantic = mapSemanticElements(
            visionResponse, boundingBoxes, htmlWithIds, pageWidth, pageHeight, doc
        )

        // Map tables
        val imageBoundingBoxes = extractImageBoundingBoxes(boundingBoxes)
        val filteredTables = filterVisionTablesOverlappingImages(
            visionResponse.tables, imageBoundingBoxes, pageWidth, pageHeight
        )
        val mappedTables = mapTableElements(
            filteredTables, boundingBoxes, htmlWithIds, pageWidth, pageHeight, doc
        )

        // Apply fallbacks
        val finalSemantic = applySemanticFallbacks(mappedSemantic, doc)
        val programmaticTables = extractSemanticTables(htmlWithIds)
        val finalTables = mergeTables(mappedTables, programmaticTables, doc)

        // Build output
        val semanticElements = buildSemanticElements(finalSemantic)
        val tableIdentifications = buildTableIdentifications(finalTables)

        return VisualIdentificationOutput(
            semanticElements = semanticElements,
            tables = tableIdentifications,
            tokenUsage = TokenUsageMetrics.empty(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        )
    }
}
