package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableIdentificationBatchRequest
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableIdentificationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.services.BatchContentRequest
import io.deepsearch.domain.services.ICssSelectorConstructionService
import io.deepsearch.domain.services.IImageDimensionService
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
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

class TableIdentificationAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val cssSelectorConstructionService: ICssSelectorConstructionService,
    private val imageDimensionService: IImageDimensionService,
    private val dispatcherProvider: IDispatcherProvider
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

    // ========== Vision-Based Detection Schema ==========
    // Uses official Gemini object detection format: box_2d = [ymin, xmin, ymax, xmax] scaled to [0, 1000]
    // See: https://ai.google.dev/gemini-api/docs/image-understanding#object-detection

    private val visionOutputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("List of table bounding boxes identified visually")
        .properties(
            mapOf(
                "tables" to Schema.builder()
                    .type("ARRAY")
                    .description("Array of visually identified tables with bounding boxes")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                mapOf(
                                    "box_2d" to Schema.builder().type("ARRAY")
                                        .description("Bounding box as [ymin, xmin, ymax, xmax] scaled to [0, 1000]")
                                        .items(Schema.builder().type("INTEGER").build())
                                        .build(),
                                    "label" to Schema.builder().type("STRING")
                                        .description("Brief description of the table's content")
                                        .build()
                                )
                            )
                            .required(listOf("box_2d", "label"))
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("tables"))
        .build()

    private val visionSystemInstruction = """
        Detect all table or grid structures in this webpage screenshot.
        
        Instructions:
        - Identify all tables in the webpage
        - Return bounding boxes using box_2d format: [ymin, xmin, ymax, xmax] where coordinates are scaled to [0, 1000].
            - ymin/ymax: vertical position (0 = top, 1000 = bottom)
            - xmin/xmax: horizontal position (0 = left, 1000 = right)
        - Generate a brief description of the table's content
        
        Expected output format:
        {
            "tables": [
                {
                    "box_2d": [ymin, xmin, ymax, xmax],
                    "label": "brief description of the table's content"
                }
            ]
        }
    """.trimIndent()

    // ========== HTML-Based Detection Schema ==========

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
                                    "specialConsideration" to Schema.builder().type("STRING")
                                        .description("Notes about non-standard table structure or styling that may affect interpretation (e.g., nested rows, irregular colspan, CSS-grid layout). Null if table has standard structure.")
                                        .nullable(true)
                                        .build()
                                )
                            )
                            .required(listOf("id"))
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
        - If the table has non-standard structure or complex styling that may affect interpretation, provide a specialConsideration note (e.g., "Uses CSS grid with irregular column spans", "Nested accordion rows")

        Expected output shape:
        {
            "tables": [
                {
                    "id": "string",
                    "specialConsideration": "string" | null
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
        val specialConsideration: String? = null
    )

    @Serializable
    private data class VisionTableIdentificationResponse(
        val tables: List<VisionTableResult>
    )

    /**
     * Vision detection result using official Gemini format.
     * box_2d format: [ymin, xmin, ymax, xmax] scaled to [0, 1000]
     */
    @Serializable
    private data class VisionTableResult(
        @kotlinx.serialization.SerialName("box_2d")
        val box2d: List<Int>,
        val label: String
    ) {
        // Helper properties to extract coordinates
        val ymin: Int get() = box2d.getOrElse(0) { 0 }
        val xmin: Int get() = box2d.getOrElse(1) { 0 }
        val ymax: Int get() = box2d.getOrElse(2) { 1000 }
        val xmax: Int get() = box2d.getOrElse(3) { 1000 }
    }

    override suspend fun generate(input: TableIdentificationInput): TableIdentificationOutput = coroutineScope {
        // HTML already has data-ds-id attributes from browser's injectStableIds()
        val htmlWithIds = input.pageSnapshot.html
        val boundingBoxes = input.pageSnapshot.boundingBoxes
        val hiddenContainers = input.pageSnapshot.hiddenContainers
        val screenshot = input.screenshot

        logger.debug(
            "Table identification: HTML={} bytes, screenshot={} bytes, {} hidden containers",
            htmlWithIds.length, screenshot.bytes.size, hiddenContainers.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId

        // Extract semantic <table> elements programmatically (no LLM needed)
        val (programmaticTables, _) = extractSemanticTables(htmlWithIds)
        logger.debug("Programmatically extracted {} semantic <table> elements", programmaticTables.size)

        // ========== Parallel Detection ==========
        // 1. Vision detection for visible content
        // 2. HTML detection for each hidden container (in parallel)

        // Pre-compute image bounding boxes for filtering graphical tables
        val imageBoundingBoxes = extractImageBoundingBoxes(boundingBoxes)
        val (screenshotWidth, screenshotHeight) = imageDimensionService.getImageDimensions(screenshot.bytes)
        val pageWidth = screenshotWidth.toDouble()
        val pageHeight = screenshotHeight.toDouble()
        
        logger.debug("Found {} image bounding boxes for filtering", imageBoundingBoxes.size)

        val visionDeferred = async {
            val (tables, usage) = detectTablesWithVision(screenshot, modelId)
            
            // Filter out vision-detected tables that overlap with image elements
            // (tables rendered as images can't be extracted from DOM)
            val filteredTables = filterVisionTablesOverlappingImages(
                tables, imageBoundingBoxes, pageWidth, pageHeight
            )
            
            if (filteredTables.size < tables.size) {
                logger.debug(
                    "Vision detection: {} tables found, {} after image filtering",
                    tables.size, filteredTables.size
                )
            }
            
            val mapped = mapVisionTablesToDomElements(filteredTables, boundingBoxes, htmlWithIds, screenshot.bytes)
            logger.debug("Vision detection: {} mapped to DOM", mapped.size)
            mapped to usage
        }

        val hiddenDeferreds = hiddenContainers.map { container ->
            async {
                detectTablesInHiddenContainer(container, htmlWithIds, modelId)
            }
        }

        // Await all results
        val (visionTables, visionUsage) = visionDeferred.await()
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

        // ========== Deduplicate: Remove vision tables that overlap with programmatic tables ==========
        // Vision detection may find the same <table> element that was already extracted programmatically
        val doc = Jsoup.parse(htmlWithIds)
        val programmaticIds = programmaticTables.map { it.id }.toSet()

        val deduplicatedVisionTables = visionTables.filter { visionTable ->
            val visionElement = doc.select("[data-ds-id=\"${visionTable.id}\"]").firstOrNull()
                ?: return@filter true // Keep if element not found (shouldn't happen)

            // Check if this vision table is a descendant, ancestor, or same as any programmatic table
            val isOverlapping = programmaticIds.any { programmaticId ->
                val programmaticElement = doc.select("[data-ds-id=\"$programmaticId\"]").firstOrNull()
                    ?: return@any false
                // Skip if vision is inside programmatic, or programmatic is inside vision, or same element
                visionElement.parents().contains(programmaticElement) ||
                        programmaticElement.parents().contains(visionElement) ||
                        visionElement == programmaticElement
            }

            if (isOverlapping) {
                logger.debug("Deduplicating vision table '{}' - overlaps with programmatic table", visionTable.id)
            }
            !isOverlapping
        }

        // ========== Deduplicate: Remove hidden tables that overlap with programmatic/vision tables ==========
        // A hidden table might be a descendant or ancestor of a programmatic or vision-detected table
        val combinedIds = (programmaticIds + deduplicatedVisionTables.map { it.id }).toSet()

        val deduplicatedHiddenTables = hiddenTables.filter { hiddenTable ->
            val hiddenElement = doc.select("[data-ds-id=\"${hiddenTable.id}\"]").firstOrNull()
                ?: return@filter true // Keep if element not found (shouldn't happen)

            // Check if this hidden table is a descendant or ancestor of any combined table
            val isOverlapping = combinedIds.any { combinedId ->
                val combinedElement = doc.select("[data-ds-id=\"$combinedId\"]").firstOrNull()
                    ?: return@any false
                // Skip if hidden is inside combined, or combined is inside hidden
                hiddenElement.parents().contains(combinedElement) ||
                        combinedElement.parents().contains(hiddenElement) ||
                        hiddenElement == combinedElement
            }

            if (isOverlapping) {
                logger.debug("Deduplicating hidden table '{}' - overlaps with programmatic/vision table", hiddenTable.id)
            }
            !isOverlapping
        }

        val allTableResults = programmaticTables + deduplicatedVisionTables + deduplicatedHiddenTables

        logger.debug(
            "Total tables: {} ({} semantic, {} vision after dedup from {}, {} hidden after dedup from {})",
            allTableResults.size, programmaticTables.size, deduplicatedVisionTables.size, visionTables.size,
            deduplicatedHiddenTables.size, hiddenTables.size
        )

        // ========== Construct CSS Selectors ==========
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
                logger.warn("Skipping table '{}' - could not construct CSS selector", llmResult.id)
                return@mapNotNull null
            }
            ResolvedTable(llmResult, cssSelector)
        }

        val docForMediaDetection = Jsoup.parse(htmlWithIds)
        val tableIdentifications = resolvedTables.map { resolved ->
            TableIdentification(
                cssSelector = resolved.cssSelector,
                dataId = resolved.llmResult.id,
                auxiliaryInfo = resolved.llmResult.specialConsideration ?: "",
                containsMedia = detectMediaInTable(resolved.llmResult.id, docForMediaDetection)
            )
        }

//        // ========== Post-process: Merge Sibling Tables ==========
//        val mergedTables = mergeSiblingTables(tableIdentifications, htmlWithIds)
//        logger.debug("Final result: {} tables (before merge: {})", mergedTables.size, tableIdentifications.size)
        logger.debug("Final result: {} tables", tableIdentifications.size)

        TableIdentificationOutput(tables = tableIdentifications, tokenUsage = tokenUsage)
    }

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

    private fun extractSemanticTables(htmlWithIds: String): Pair<List<LlmTableResult>, String> {
        val doc = Jsoup.parse(htmlWithIds)
        val tables = mutableListOf<LlmTableResult>()

        val tableElements = doc.select("table[data-ds-id]")

        tableElements.forEach { element ->
            val id = element.attr("data-ds-id")
            // Programmatically extracted tables don't have special considerations
            tables.add(LlmTableResult(id, null))
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

    // ========== Batch Processing Methods ==========

    private val batchJson = Json { ignoreUnknownKeys = true }

    override fun prepareBatchRequest(
        requestId: String,
        html: String,
        screenshotBase64: String?,
        screenshotMimeType: String?,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>?,
        pageWidth: Double?,
        pageHeight: Double?
    ): TableIdentificationBatchRequest {
        // HTML already has data-ds-id attributes from browser's injectStableIds()
        val htmlWithIds = html

        // Apply programmatic extraction to reduce LLM workload (same as interactive mode)
        val (programmaticTables, reducedHtml) = extractSemanticTables(htmlWithIds)

        // Use vision if screenshot is provided, otherwise fall back to HTML-based detection
        val request = if (screenshotBase64 != null && screenshotMimeType != null) {
            // Vision-based batch request (same as interactive mode)
            val metadata = mutableMapOf(
                "programmaticTables" to batchJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(LlmTableResult.serializer()),
                    programmaticTables
                ),
                "useVision" to "true"
            )
            // Store page dimensions for vision mapping
            if (pageWidth != null && pageHeight != null) {
                metadata["pageWidth"] = pageWidth.toString()
                metadata["pageHeight"] = pageHeight.toString()
            }

            BatchContentRequest(
                requestId = requestId,
                modelId = ModelIds.GEMINI_2_5_FLASH.modelId, // Vision model
                systemInstruction = visionSystemInstruction,
                userPrompt = "Identify all tables in this full-page screenshot. Include data tables, comparison tables, and grid layouts.",
                imageData = screenshotBase64,
                imageMimeType = screenshotMimeType,
                temperature = 0f,
                metadata = metadata
            ).withSchema(visionOutputSchema)
        } else {
            // HTML-based batch request (fallback)
            val cleanedHtml = cleanHtml(reducedHtml)
            BatchContentRequest(
                requestId = requestId,
                modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
                systemInstruction = systemInstruction,
                userPrompt = cleanedHtml,
                temperature = 0f,
                metadata = mapOf(
                    "programmaticTables" to batchJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(LlmTableResult.serializer()),
                        programmaticTables
                    ),
                    "useVision" to "false"
                )
            ).withSchema(outputSchema)
        }

        return TableIdentificationBatchRequest(
            request = request,
            htmlWithIds = htmlWithIds
        )
    }

    override fun parseBatchResponse(
        responseText: String,
        htmlWithIds: String,
        metadata: Map<String, String>?,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>?,
        pageWidth: Double?,
        pageHeight: Double?
    ): List<TableIdentification> {
        val useVision = metadata?.get("useVision") == "true"

        return try {
            if (useVision) {
                parseBatchResponseVision(responseText, htmlWithIds, metadata, boundingBoxes, pageWidth, pageHeight)
            } else {
                parseBatchResponseHtml(responseText, htmlWithIds, metadata)
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: {}", e.message)
            emptyList()
        }
    }

    private fun parseBatchResponseHtml(
        responseText: String,
        htmlWithIds: String,
        metadata: Map<String, String>?
    ): List<TableIdentification> {
        val response = batchJson.decodeFromString<TableIdentificationResponse>(responseText)

        // Merge programmatic tables (from metadata) with LLM results
        val programmaticTables = metadata?.get("programmaticTables")?.let {
            batchJson.decodeFromString<List<LlmTableResult>>(it)
        } ?: emptyList()

        val allTableResults = programmaticTables + response.tables

        if (programmaticTables.isNotEmpty()) {
            logger.debug(
                "Batch parsing (HTML): {} programmatic + {} LLM = {} total tables",
                programmaticTables.size, response.tables.size, allTableResults.size
            )
        }

        // Build CSS selectors for all tables
        val identifiers = allTableResults.map { it.id }
        val cssSelectorsMap = cssSelectorConstructionService.constructCssSelectorsFromIdentifiers(
            identifiers = identifiers,
            htmlWithIdentifiers = htmlWithIds
        )

        // Pre-parse HTML for media detection
        val docForMediaDetection = Jsoup.parse(htmlWithIds)

        return allTableResults.mapNotNull { llmResult ->
            val cssSelector = cssSelectorsMap[llmResult.id]
            if (cssSelector == null) {
                logger.warn("Skipping table '{}' - could not construct CSS selector", llmResult.id)
                return@mapNotNull null
            }

            val containsMedia = detectMediaInTable(llmResult.id, docForMediaDetection)

            TableIdentification(
                cssSelector = cssSelector,
                dataId = llmResult.id,
                auxiliaryInfo = llmResult.specialConsideration ?: "",
                containsMedia = containsMedia
            )
        }
    }

    private fun parseBatchResponseVision(
        responseText: String,
        htmlWithIds: String,
        metadata: Map<String, String>?,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>?,
        pageWidth: Double?,
        pageHeight: Double?
    ): List<TableIdentification> {
        val visionResponse = batchJson.decodeFromString<VisionTableIdentificationResponse>(responseText)
        val doc = Jsoup.parse(htmlWithIds)

        // Merge programmatic tables from metadata
        val programmaticTables = metadata?.get("programmaticTables")?.let {
            batchJson.decodeFromString<List<LlmTableResult>>(it)
        } ?: emptyList()

        // If bounding boxes are available, do proper IoU mapping like interactive mode
        val visionMappedTables = if (boundingBoxes != null && boundingBoxes.isNotEmpty()) {
            // Get page dimensions from metadata or parameters
            val width = pageWidth
                ?: metadata?.get("pageWidth")?.toDoubleOrNull()
                ?: boundingBoxes.values.maxOfOrNull { it.right }
                ?: 1920.0
            val height = pageHeight
                ?: metadata?.get("pageHeight")?.toDoubleOrNull()
                ?: boundingBoxes.values.maxOfOrNull { it.bottom }
                ?: 1080.0

            // Map vision results to DOM elements using IoU (same logic as interactive mode)
            mapVisionToDomElementsBatch(visionResponse.tables, htmlWithIds, boundingBoxes, width, height, doc)
        } else {
            // No bounding boxes available - can't do IoU mapping, use programmatic only
            logger.debug("Batch parsing (Vision): No bounding boxes available, using programmatic only")
            emptyList()
        }

        logger.debug(
            "Batch parsing (Vision): {} vision tables mapped + {} programmatic tables",
            visionMappedTables.size, programmaticTables.size
        )

        // Combine programmatic and vision-mapped tables (deduplicated by ID)
        val allIdentifiers = (programmaticTables.map { it.id } + visionMappedTables.map { it.dataId }).distinct()
        val cssSelectorsMap = cssSelectorConstructionService.constructCssSelectorsFromIdentifiers(
            identifiers = allIdentifiers,
            htmlWithIdentifiers = htmlWithIds
        )

        // Build results from programmatic tables
        val programmaticResults = programmaticTables.mapNotNull { llmResult ->
            val cssSelector = cssSelectorsMap[llmResult.id]
            if (cssSelector == null) {
                logger.warn("Skipping table '{}' - could not construct CSS selector", llmResult.id)
                return@mapNotNull null
            }

            val containsMedia = detectMediaInTable(llmResult.id, doc)

            TableIdentification(
                cssSelector = cssSelector,
                dataId = llmResult.id,
                auxiliaryInfo = llmResult.specialConsideration ?: "",
                containsMedia = containsMedia
            )
        }

        // Combine, preferring vision results for duplicates (they have better descriptions)
        val programmaticIds = programmaticResults.map { it.dataId }.toSet()
        val uniqueVisionTables = visionMappedTables.filter { it.dataId !in programmaticIds }

        return programmaticResults + uniqueVisionTables
    }

    /**
     * Map vision-detected tables to DOM elements using IoU (batch mode).
     */
    private fun mapVisionToDomElementsBatch(
        visionTables: List<VisionTableResult>,
        htmlWithIds: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        pageWidth: Double,
        pageHeight: Double,
        doc: org.jsoup.nodes.Document
    ): List<TableIdentification> {
        val mappedTables = mutableListOf<TableIdentification>()

        for (visionTable in visionTables) {
            // Convert [0, 1000] scaled coordinates to absolute pixel coordinates
            // box_2d format: [ymin, xmin, ymax, xmax]
            val targetTop = visionTable.ymin * pageHeight / 1000
            val targetLeft = visionTable.xmin * pageWidth / 1000
            val targetBottom = visionTable.ymax * pageHeight / 1000
            val targetRight = visionTable.xmax * pageWidth / 1000
            val targetArea = (targetRight - targetLeft) * (targetBottom - targetTop)

            if (targetArea <= 0) continue

            // Find best matching element using IoU + containment score
            var bestMatch: Triple<String, Double, String>? = null // xpath, score, dataId

            for ((xpath, elementBox) in boundingBoxes) {
                val elementLeft = elementBox.left
                val elementTop = elementBox.top
                val elementRight = elementBox.right
                val elementBottom = elementBox.bottom
                val elementArea = (elementRight - elementLeft) * (elementBottom - elementTop)

                if (elementArea <= 0) continue

                // Calculate intersection
                val intersectLeft = maxOf(targetLeft, elementLeft)
                val intersectTop = maxOf(targetTop, elementTop)
                val intersectRight = minOf(targetRight, elementRight)
                val intersectBottom = minOf(targetBottom, elementBottom)

                val intersectWidth = maxOf(0.0, intersectRight - intersectLeft)
                val intersectHeight = maxOf(0.0, intersectBottom - intersectTop)
                val intersectArea = intersectWidth * intersectHeight

                if (intersectArea > 0) {
                    // IoU: intersection / union
                    val unionArea = targetArea + elementArea - intersectArea
                    val iou = if (unionArea > 0) intersectArea / unionArea else 0.0

                    // Vision coverage: what % of vision box is covered by element
                    val visionCoverage = intersectArea / targetArea

                    // Combined score: prefer elements that cover most of the vision box
                    val score = maxOf(iou, visionCoverage * 0.8)

                    // No threshold - always pick best match to avoid false negatives
                    val element = findElementByXPath(doc, xpath)
                    val dataId = element?.attr("data-ds-id") ?: ""
                    if (dataId.isNotEmpty() && (bestMatch == null || score > bestMatch.second)) {
                        bestMatch = Triple(xpath, score, dataId)
                    }
                }
            }

            if (bestMatch != null) {
                val element = findElementByXPath(doc, bestMatch.first)!!
                val dataId = bestMatch.third
                val cssSelector = cssSelectorConstructionService.constructCssSelector(element)
                val containsMedia = detectMediaInTable(dataId, doc)

                mappedTables.add(
                    TableIdentification(
                        cssSelector = cssSelector,
                        dataId = dataId,
                        auxiliaryInfo = visionTable.label, // Vision provides label as auxiliary info
                        containsMedia = containsMedia
                    )
                )

                logger.debug("Vision table mapped to {} with score {}", dataId, "%.3f".format(bestMatch.second))
            }
        }

        return mappedTables
    }

    // ========== Vision-Based Detection Methods ==========

    /**
     * Detect tables visually using a screenshot and vision model.
     * Returns table bounding boxes as percentages of page dimensions.
     */
    private suspend fun detectTablesWithVision(
        screenshot: IBrowserPage.Screenshot,
        modelId: String
    ): Pair<List<VisionTableResult>, TokenUsageMetrics> {
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<VisionTableIdentificationResponse>(this@TableIdentificationAgentGenAiImpl::class.simpleName!! + "_vision") {
                val result = client.models.generateContent(
                    modelId,
                    listOf(
                        Content.fromParts(
                            Part.fromBytes(screenshot.bytes, screenshot.mimeType.value),
                            Part.fromText("Identify all table/grid structures in this webpage screenshot.")
                        )
                    ),
                    GenerateContentConfig.builder()
                        .temperature(0F)
                        .responseSchema(visionOutputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingBudget(0)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(visionSystemInstruction)))
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

        return response.tables to tokenUsage
    }

    /**
     * Detect tables in a hidden container using HTML-based LLM detection.
     * 
     * Hidden containers (accordion panels, collapsed sections, etc.) are not visible in screenshots,
     * so we process their HTML snippets individually with higher fidelity since they are smaller.
     * 
     * Note: container.html already has data-ds-id attributes from the browser's injectStableIds()
     */
    private suspend fun detectTablesInHiddenContainer(
        container: IBrowserPage.HiddenContainer,
        fullHtmlWithIds: String,
        modelId: String
    ): Pair<List<LlmTableResult>, TokenUsageMetrics> {
        // container.html already has data-ds-id attributes from browser injection
        val cleanedSnippet = cleanHtml(container.html)

        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<TableIdentificationResponse>(this@TableIdentificationAgentGenAiImpl::class.simpleName!! + "_hidden") {
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(Part.fromText(cleanedSnippet))),
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

        // The IDs in response are relative to the snippet - need to verify they exist in the full HTML
        val validatedTables = response.tables.filter { table ->
            val doc = Jsoup.parse(fullHtmlWithIds)
            doc.select("[data-ds-id=\"${table.id}\"]").isNotEmpty()
        }

        return validatedTables to tokenUsage
    }

    /**
     * Map vision-detected table bounding boxes to DOM elements.
     * Finds the best matching element based on bounding box overlap.
     * 
     * Uses a combination of IoU and containment metrics for robust matching.
     */
    private fun mapVisionTablesToDomElements(
        visionTables: List<VisionTableResult>,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        htmlWithIds: String,
        screenshotBytes: ByteArray
    ): List<LlmTableResult> {
        if (visionTables.isEmpty() || pageBoundingBoxes.isEmpty()) {
            return emptyList()
        }

        // Get actual screenshot dimensions
        val (screenshotWidth, screenshotHeight) = imageDimensionService.getImageDimensions(screenshotBytes)
        val pageWidth = screenshotWidth.toDouble()
        val pageHeight = screenshotHeight.toDouble()

        logger.debug("Vision mapping dimensions: {}x{}", pageWidth.toInt(), pageHeight.toInt())

        val doc = Jsoup.parse(htmlWithIds)
        val results = mutableListOf<LlmTableResult>()

        for (visionTable in visionTables) {
            // Convert [0, 1000] scaled coordinates to absolute pixel coordinates
            // box_2d format: [ymin, xmin, ymax, xmax]
            val targetTop = visionTable.ymin * pageHeight / 1000
            val targetLeft = visionTable.xmin * pageWidth / 1000
            val targetBottom = visionTable.ymax * pageHeight / 1000
            val targetRight = visionTable.xmax * pageWidth / 1000
            val targetArea = (targetRight - targetLeft) * (targetBottom - targetTop)

            logger.debug(
                "Vision table '{}' at box_2d={} -> pixels: ({}, {}) to ({}, {})",
                visionTable.label.take(40),
                visionTable.box2d,
                targetLeft.toInt(), targetTop.toInt(),
                targetRight.toInt(), targetBottom.toInt()
            )

            if (targetArea <= 0) {
                logger.debug("Skipping vision table with zero area")
                continue
            }

            // Find elements with data-ds-id that best match this region
            var bestMatch: Triple<String, Double, String>? = null // xpath, score, dataId
            var topCandidates = mutableListOf<Triple<String, Double, String>>() // For debugging

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

                    // IoU: intersection / union
                    val unionArea = targetArea + elemArea - interArea
                    val iou = if (unionArea > 0) interArea / unionArea else 0.0

                    // Containment: what % of vision box is covered by element
                    val visionCoverage = interArea / targetArea
                    // Containment: what % of element is covered by vision box
                    val elementCoverage = interArea / elemArea

                    // Combined score: prefer elements that match the vision box size
                    // - IoU is the best metric (1.0 for perfect match)
                    // - For elements larger than vision box: use geometric mean of coverages
                    //   This penalizes oversized containers (elementCoverage will be low)
                    // - Allow some padding (elements up to ~3x larger still get reasonable scores)
                    val coverageScore = kotlin.math.sqrt(visionCoverage * elementCoverage)
                    val score = maxOf(iou, coverageScore)

                    // Find the data-ds-id for this element
                    val element = findElementByXPath(doc, xpath)
                    val dataId = element?.attr("data-ds-id") ?: ""

                    if (dataId.isNotEmpty()) {
                        // Track all candidates with any overlap for best match selection
                        // We don't filter by score threshold - we always pick the best match
                        // to avoid false negatives (missing real tables is worse than false positives)
                        topCandidates.add(Triple(xpath, score, dataId))
                        if (bestMatch == null || score > bestMatch.second) {
                            bestMatch = Triple(xpath, score, dataId)
                            // Log detailed comparison for best match
                            logger.debug(
                                "  Best match so far: {} IoU={}, visionCov={}, elemCov={}",
                                dataId,
                                "%.3f".format(iou),
                                "%.3f".format(visionCoverage),
                                "%.3f".format(elementCoverage)
                            )
                            logger.debug(
                                "    Vision box: ({}, {}) to ({}, {}) = {}x{} px",
                                targetLeft.toInt(), targetTop.toInt(), targetRight.toInt(), targetBottom.toInt(),
                                (targetRight - targetLeft).toInt(), (targetBottom - targetTop).toInt()
                            )
                            logger.debug(
                                "    DOM element: ({}, {}) to ({}, {}) = {}x{} px",
                                elemLeft.toInt(), elemTop.toInt(), elemRight.toInt(), elemBottom.toInt(),
                                (elemRight - elemLeft).toInt(), (elemBottom - elemTop).toInt()
                            )
                        }
                    }
                }
            }

            // Log top candidates for debugging
            if (topCandidates.isEmpty()) {
                logger.debug("No candidates found for vision table. Checking element positions...")
                // Find elements in the general area for debugging
                val nearbyElements = pageBoundingBoxes.entries
                    .filter { it.value.top < targetBottom + 100 && it.value.bottom > targetTop - 100 }
                    .take(5)
                nearbyElements.forEach { entry ->
                    val bbox = entry.value
                    logger.debug(
                        "  Nearby element at ({}, {}) to ({}, {}): {}",
                        bbox.left.toInt(), bbox.top.toInt(), bbox.right.toInt(), bbox.bottom.toInt(),
                        entry.key.take(60)
                    )
                }
            } else {
                topCandidates.sortedByDescending { it.second }.take(3).forEach { candidate ->
                    logger.debug("  Candidate: {} with score {}", candidate.third, "%.3f".format(candidate.second))
                }
            }

            // Always use the best match - we trust the LLM's vision detection
            // False negatives (missing tables) are worse than false positives
            // Table Interpretation handles non-tables gracefully by converting to markdown anyway
            if (bestMatch != null) {
                val scoreLabel = when {
                    bestMatch.second >= 0.8 -> "excellent"
                    bestMatch.second >= 0.6 -> "good"
                    bestMatch.second >= 0.4 -> "moderate"
                    else -> "low (may be inaccurate)"
                }
                logger.debug(
                    "Vision table mapped to {} with {} score {}",
                    bestMatch.third, scoreLabel, "%.3f".format(bestMatch.second)
                )
                results.add(
                    LlmTableResult(
                        id = bestMatch.third, // dataId
                        specialConsideration = null // Vision-detected tables don't have special considerations
                    )
                )
            } else {
                logger.debug("No suitable match found for vision table")
            }
        }

        return results.distinctBy { it.id }
    }


    /**
     * Find element by relative XPath in a Jsoup document.
     */
    private fun findElementByXPath(doc: Document, xpath: String): org.jsoup.nodes.Element? {
        // XPath format: ./tagname[index]/tagname[index]/...
        if (!xpath.startsWith("./")) return null

        val xpathRegex = Regex("""([a-zA-Z0-9_\-:]+)\[(\d+)]""")
        var current: org.jsoup.nodes.Element = doc.body() ?: return null

        val parts = xpath.removePrefix("./").split("/").filter { it.isNotEmpty() }
        for (part in parts) {
            val match = xpathRegex.matchEntire(part) ?: return null
            val tagName = match.groupValues[1]
            val index = match.groupValues[2].toInt() - 1 // XPath is 1-indexed

            val children = current.children().filter { it.tagName().equals(tagName, ignoreCase = true) }
            current = children.getOrNull(index) ?: return null
        }

        return current
    }

    /**
     * Merge sibling tables that share a common parent container.
     * This addresses the "rows as tables" problem where each row is identified as a separate table.
     */
    private fun mergeSiblingTables(
        tables: List<TableIdentification>,
        htmlWithIds: String
    ): List<TableIdentification> {
        if (tables.size <= 1) return tables

        val doc = Jsoup.parse(htmlWithIds)

        // Group tables by their parent's data-ds-id
        val tablesByParent = tables.groupBy { table ->
            val element = doc.select("[data-ds-id=\"${table.dataId}\"]").firstOrNull()
            val parent = element?.parent()
            parent?.attr("data-ds-id")?.takeIf { it.isNotEmpty() }
        }

        val result = mutableListOf<TableIdentification>()

        for ((parentId, siblingTables) in tablesByParent) {
            if (parentId == null) {
                // No common parent, keep as-is
                result.addAll(siblingTables)
                continue
            }

            // If 3+ siblings are identified as tables, they're likely rows of a single table
            // Merge them into the parent container
            if (siblingTables.size >= 3) {
                val parentElement = doc.select("[data-ds-id=\"$parentId\"]").firstOrNull()
                if (parentElement != null) {
                    // Check if most direct children are in the tables list
                    val directChildIds = parentElement.children()
                        .mapNotNull { it.attr("data-ds-id").takeIf { id -> id.isNotEmpty() } }
                        .toSet()
                    val tableIds = siblingTables.map { it.dataId }.toSet()
                    val overlapRatio =
                        tableIds.count { it in directChildIds }.toDouble() / directChildIds.size.coerceAtLeast(1)

                    if (overlapRatio >= 0.5) {
                        // Merge: create one table from the parent
                        val parentCssSelector = cssSelectorConstructionService.constructCssSelectorsFromIdentifiers(
                            listOf(parentId), htmlWithIds
                        )[parentId]

                        if (parentCssSelector != null) {
                            val containsMedia = siblingTables.any { it.containsMedia }
                            val combinedDescription = siblingTables.firstOrNull()?.auxiliaryInfo ?: ""

                            logger.debug("Merged {} sibling tables into parent {}", siblingTables.size, parentId)
                            result.add(
                                TableIdentification(
                                    cssSelector = parentCssSelector,
                                    dataId = parentId,
                                    auxiliaryInfo = combinedDescription,
                                    containsMedia = containsMedia
                                )
                            )
                            continue
                        }
                    }
                }
            }

            // Couldn't merge, keep original tables
            result.addAll(siblingTables)
        }

        return result
    }

    // ========== Image Overlap Filtering ==========

    /**
     * Extract bounding boxes for image elements from page bounding boxes.
     * Image elements are identified by XPath containing "img[" or "picture[".
     */
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

    /**
     * Filter out vision-detected tables that significantly overlap with image elements.
     * Tables rendered as images (graphical tables) can't be extracted from DOM.
     * 
     * @param visionTables Raw vision detection results
     * @param imageBoundingBoxes Bounding boxes of image elements on the page
     * @param pageWidth Page width in pixels
     * @param pageHeight Page height in pixels
     * @return Filtered list of vision tables that don't overlap with images
     */
    private fun filterVisionTablesOverlappingImages(
        visionTables: List<VisionTableResult>,
        imageBoundingBoxes: List<IBrowserPage.BoundingBox>,
        pageWidth: Double,
        pageHeight: Double
    ): List<VisionTableResult> {
        if (imageBoundingBoxes.isEmpty()) {
            return visionTables
        }

        return visionTables.filter { visionTable ->
            // Convert vision coordinates (scaled 0-1000) to absolute pixels
            val visionTop = visionTable.ymin * pageHeight / 1000
            val visionLeft = visionTable.xmin * pageWidth / 1000
            val visionBottom = visionTable.ymax * pageHeight / 1000
            val visionRight = visionTable.xmax * pageWidth / 1000
            val visionArea = (visionRight - visionLeft) * (visionBottom - visionTop)

            if (visionArea <= 0) return@filter true // Keep if zero area (edge case)

            val overlapsImage = imageBoundingBoxes.any { imgBbox ->
                // Calculate intersection
                val interLeft = maxOf(visionLeft, imgBbox.left)
                val interTop = maxOf(visionTop, imgBbox.top)
                val interRight = minOf(visionRight, imgBbox.right)
                val interBottom = minOf(visionBottom, imgBbox.bottom)

                if (interLeft >= interRight || interTop >= interBottom) {
                    return@any false // No intersection
                }

                val interArea = (interRight - interLeft) * (interBottom - interTop)
                val imgArea = (imgBbox.right - imgBbox.left) * (imgBbox.bottom - imgBbox.top)

                // Check if vision table is mostly inside an image (>70% overlap)
                val visionCoverage = interArea / visionArea

                // Also check IoU for tables that might be the same size as the image
                val unionArea = visionArea + imgArea - interArea
                val iou = if (unionArea > 0) interArea / unionArea else 0.0

                visionCoverage > 0.7 || iou > 0.5
            }

            if (overlapsImage) {
                logger.debug(
                    "Filtering out vision table '{}' - overlaps with image element",
                    visionTable.label.take(40)
                )
            }

            !overlapsImage
        }
    }

}
