package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IVisualIdentificationAgent
import io.deepsearch.domain.agents.MobileLayoutIdentification
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.VisualIdentificationBatchRequest
import io.deepsearch.domain.models.entities.CachedHiddenMobileLayout
import io.deepsearch.domain.models.entities.CachedHiddenTable
import io.deepsearch.domain.models.entities.HiddenContainerTableCache
import io.deepsearch.domain.repositories.IHiddenContainerTableCacheRepository
import java.security.MessageDigest
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
    private val dispatcherProvider: IDispatcherProvider,
    private val hiddenContainerCacheRepository: IHiddenContainerTableCacheRepository
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
        
        Example output:
        {
            "header": {"box_2d": [0, 0, 50, 1000], "label": "Navigation bar with logo"},
            "footer": {"box_2d": [920, 0, 1000, 1000], "label": "Footer with links"},
            "tables": [
                {"box_2d": [200, 50, 600, 950], "label": "Pricing comparison table"}
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
        val label: String
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

    // ========== HTML-Based Hidden Container Analysis Schema ==========
    // For detecting tables and navigation menus in hidden containers (accordions, tabs, mobile menus, etc.)

    private val hiddenContainerAnalysisSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Analysis of hidden container content - tables and mobile layouts")
        .properties(
            mapOf(
                "tables" to Schema.builder()
                    .type("ARRAY")
                    .description("Array of table/grid structures identified")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                mapOf(
                                    "id" to Schema.builder().type("STRING")
                                        .description("The data-ds-id value of the table root element")
                                        .build(),
                                    "specialConsideration" to Schema.builder().type("STRING")
                                        .description("Notes about non-standard table structure or styling that may affect interpretation (e.g., nested rows, colspan issues, CSS-grid layout)")
                                        .nullable(true)
                                        .build()
                                )
                            )
                            .required(listOf("id"))
                            .build()
                    )
                                        .build(),
                "mobileLayouts" to Schema.builder()
                    .type("ARRAY")
                    .description("Array of mobile layout structures")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                mapOf(
                                    "id" to Schema.builder().type("STRING")
                                        .description("The data-ds-id value of the mobile layout root element")
                                        .build(),
                                    "description" to Schema.builder().type("STRING")
                                        .description("Brief description (e.g., 'Mobile navigation layout', 'Hamburger menu content')")
                                        .build()
                                )
                            )
                            .required(listOf("id", "description"))
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("tables", "mobileLayouts"))
        .build()

    private val hiddenContainerAnalysisInstruction = """
        Your task is to analyze the provided HTML snippet from a hidden container and identify:
        1. CSS-styled table structures
        2. Mobile layout structures

        Inputs:
        - Cleaned HTML structure with data-ds-id attributes on container elements

        === TABLE IDENTIFICATION ===
        Identify NON-SEMANTIC table/grid structures:
        - CSS Grid layouts displaying tabular data (pricing tables, feature comparisons)
        - Flexbox-based data grids
        - Div-based tables with row/column patterns
        Return the data-ds-id of the ROOT container. Note any complex styling in specialConsideration.

        === MOBILE LAYOUT IDENTIFICATION ===
        Identify mobile UI structures:
        - Mobile/hamburger menu contents
        - Responsive navigation
        - Collapsible mobile-specific UI sections
        Return the data-ds-id of the ROOT container.

        Expected output:
        {
            "tables": [
                {"id": "ds-element-123", "specialConsideration": "The "Pro" column is most popular."}
            ],
            "mobileLayouts": [
                {"id": "ds-element-456", "description": "Mobile navigation layout"}
            ]
        }
    """.trimIndent()

    @Serializable
    private data class HiddenContainerAnalysisResponse(
        val tables: List<HiddenTableResult>,
        val mobileLayouts: List<HiddenMobileLayoutResult>
    )

    @Serializable
    private data class HiddenTableResult(
        val id: String,
        val specialConsideration: String? = null
    )

    @Serializable
    private data class HiddenMobileLayoutResult(
        val id: String,
        val description: String
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
        val containsMedia: Boolean
    )

    /** Result from analyzing a hidden container - contains both tables and mobile layouts */
    private data class HiddenContainerAnalysisResult(
        val containerId: String,
        val tables: List<HiddenTableResult>,
        val mobileLayouts: List<HiddenMobileLayoutResult>
    )

    // ========== Main Generate Method ==========

    override suspend fun generate(input: VisualIdentificationInput): VisualIdentificationOutput = coroutineScope {
        // HTML already has data-ds-id attributes from browser's injectStableIds()
        val htmlWithIds = input.pageSnapshot.html
        val boundingBoxes = input.pageSnapshot.boundingBoxes
        val hiddenContainers = input.pageSnapshot.hiddenContainers
        val screenshot = input.screenshot

        logger.debug(
            "Visual identification: HTML={} bytes, screenshot={} bytes, {} bounding boxes, {} hidden containers",
            htmlWithIds.length, screenshot.bytes.size, boundingBoxes.size, hiddenContainers.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId

        // ========== Scale image for Gemini API if needed ==========
        // Very tall full-page screenshots (e.g., Wikipedia at 20,000+ px) can cause
        // "Unable to process input image" errors. We scale down for Gemini while
        // keeping the original for accurate element cropping.
        val scaledResult = withContext(dispatcherProvider.io) {
            imageDimensionService.scaleImageForGemini(screenshot.bytes, maxDimension = 4096)
        }
        
        if (scaledResult.wasScaled) {
            logger.info(
                "Scaled screenshot from {}x{} to fit within 4096px (scale factor: {:.3f})",
                scaledResult.originalWidth, scaledResult.originalHeight, scaledResult.scaleFactor
            )
        }

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
                                // Use scaled image for Gemini API
                                Part.fromBytes(scaledResult.scaledBytes, scaledResult.scaledMimeType),
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

        // Hidden container analysis with caching
        val (hiddenTables, hiddenMobileLayouts, hiddenUsage) = analyzeHiddenContainersWithCache(
            hiddenContainers, htmlWithIds, modelId
        )

        // Await vision results
        val (visionResponse, visionUsage) = visionDeferred.await()

        logger.debug(
            "Hidden container analysis: {} tables, {} mobile layouts found across {} containers",
            hiddenTables.size, hiddenMobileLayouts.size, hiddenContainers.size
        )

        val tokenUsage = visionUsage + hiddenUsage

        // Use ORIGINAL screenshot dimensions for coordinate mapping
        // The Gemini response uses normalized [0, 1000] coordinates, so we map to original dimensions
        val pageWidth = scaledResult.originalWidth.toDouble()
        val pageHeight = scaledResult.originalHeight.toDouble()

        logger.debug("Visual identification dimensions: {}x{} (original)", pageWidth.toInt(), pageHeight.toInt())

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

        // ========== Apply semantic HTML overrides ==========
        // Semantic <header>/<footer> tags take priority over vision detection
        val finalSemantic = applySemanticOverrides(mappedSemantic, doc)
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
                label = hiddenTable.specialConsideration ?: "",
                containsMedia = containsMedia
            )
        }

        val finalTables = visionAndProgrammaticTables + hiddenMappedTables

        logger.debug(
            "Total tables: {} ({} vision/programmatic, {} hidden after dedup from {})",
            finalTables.size, visionAndProgrammaticTables.size,
            hiddenMappedTables.size, hiddenTables.size
        )

        // ========== Build mobile layout identifications from hidden containers ==========
        val mobileLayoutIdentifications = hiddenMobileLayouts.map { mobileLayout ->
            MobileLayoutIdentification(
                dataId = mobileLayout.id,
                description = mobileLayout.description
            )
        }

        // ========== Build output ==========
        val semanticElements = buildSemanticElements(finalSemantic)
        val tableIdentifications = buildTableIdentifications(finalTables)

        logger.debug(
            "Visual identification complete: {} semantic elements, {} tables, {} hidden mobile layouts",
            countSemanticElements(semanticElements),
            tableIdentifications.size,
            mobileLayoutIdentifications.size
        )

        VisualIdentificationOutput(
            semanticElements = semanticElements,
            tables = tableIdentifications,
            hiddenMobileLayouts = mobileLayoutIdentifications,
            tokenUsage = tokenUsage
        )
    }

    // ========== ID Injection ==========

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
                containsMedia = containsMedia
            )
        }.distinctBy { it.dataId }
    }

    /**
     * Dispatch to the appropriate mapping algorithm based on element type.
     * - Semantic elements (header, footer, nav, etc.) use closest match algorithm
     * - Tables use IoU/coverage algorithm optimized for containment
     */
    private fun mapVisionToDomElement(
        box2d: List<Int>,
        label: String,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        pageWidth: Double,
        pageHeight: Double,
        doc: Document,
        elementType: String
    ): MappedElement? {
        // Semantic elements use closest match (minimizes edge differences)
        val semanticElementTypes = setOf("header", "footer", "navSidebar", "breadcrumb", "cookieBanner", "popups")
        if (elementType in semanticElementTypes) {
            return mapSemanticElementClosestMatch(box2d, label, pageBoundingBoxes, pageWidth, pageHeight, doc, elementType)
        }

        // Tables use IoU/coverage algorithm (optimized for containment)
        return mapTableElementIoU(box2d, label, pageBoundingBoxes, pageWidth, pageHeight, doc, elementType)
    }

    /**
     * Map a vision bounding box to a DOM element using IoU + coverage scoring.
     * This is optimized for tables which may be partially visible/collapsed,
     * so we want the container that best contains the visible portion.
     */
    private fun mapTableElementIoU(
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

        var bestMatch: Pair<String, Double>? = null // dsId, score

        // pageBoundingBoxes keys are data-ds-id values (e.g., "ds-element-123"), not XPaths
        for ((dsId, bbox) in pageBoundingBoxes) {
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

                if (dsId.isNotEmpty() && (bestMatch == null || score > bestMatch.second)) {
                    bestMatch = Pair(dsId, score)
                }
            }
        }

        return if (bestMatch != null) {
            val element = doc.select("[data-ds-id=\"${bestMatch.first}\"]").firstOrNull()
                ?: return null.also { logger.debug("Could not find element with data-ds-id={}", bestMatch.first) }
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

    /**
     * Map a vision bounding box to a DOM element by finding the container root using IoU + LCA.
     * 
     * Algorithm:
     * 1. Calculate IoU (Intersection over Union) for each element against the target bbox
     * 2. Find candidates with IoU >= threshold (elements that meaningfully match the target)
     * 3. If multiple candidates exist, compute their LCA (Lowest Common Ancestor)
     * 4. The result is the container root that encompasses all matching content
     * 
     * IoU is preferred over simple overlap because:
     * - It's scale-invariant and works well regardless of element size
     * - It penalizes both over-selection (element too big) and under-selection (element too small)
     * - Range [0,1] provides intuitive threshold tuning
     */
    private fun mapSemanticElementClosestMatch(
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

        // Step 1: Calculate IoU for each element and find candidates
        // IoU threshold of 0.8 means ~90% agreement between element and target
        val iouThreshold = 0.8
        
        data class Candidate(
            val element: org.jsoup.nodes.Element,
            val dsId: String,
            val iou: Double
        )
        
        val candidates = mutableListOf<Candidate>()
        
        // Debug: log target region
        logger.debug(
            "IoU {} target region: [{}, {}, {}, {}] (area={})",
            elementType,
            "%.1f".format(targetLeft), "%.1f".format(targetTop),
            "%.1f".format(targetRight), "%.1f".format(targetBottom),
            "%.0f".format(targetArea)
        )
        
        for ((dsId, bbox) in pageBoundingBoxes) {
            val iou = calculateIoU(bbox, targetTop, targetLeft, targetBottom, targetRight, targetArea)
            
            // Debug: log first few elements and any with high IoU
            if (dsId in listOf("ds-element-0", "ds-element-1", "ds-element-2", "ds-element-3", "ds-element-4") || iou >= 0.5) {
                val element = doc.selectFirst("[data-ds-id=\"$dsId\"]")
                val tag = element?.tagName() ?: "?"
                val bboxArea = (bbox.right - bbox.left) * (bbox.bottom - bbox.top)
                logger.debug(
                    "IoU {} candidate {}: tag=<{}>, bbox=[{}, {}, {}, {}] (area={}), IoU={}",
                    elementType, dsId, tag,
                    "%.1f".format(bbox.left), "%.1f".format(bbox.top),
                    "%.1f".format(bbox.right), "%.1f".format(bbox.bottom),
                    "%.0f".format(bboxArea),
                    "%.3f".format(iou)
                )
            }
            
            if (iou >= iouThreshold) {
                val element = doc.selectFirst("[data-ds-id=\"$dsId\"]") ?: continue
                // Exclude body, html, and script/style elements
                if (element.tagName() in listOf("body", "html", "script", "style", "noscript")) continue
                candidates.add(Candidate(element, dsId, iou))
            }
        }

        if (candidates.isEmpty()) {
            logger.debug("No candidates with IoU >= {} found for visual {} region", iouThreshold, elementType)
            return null
        }

        // Sort by IoU descending
        val sortedCandidates = candidates.sortedByDescending { it.iou }
        val containerRoot = findLowestCommonAncestor(sortedCandidates.map { it.element })

        // Validate: don't return body/html as container
        if (containerRoot.tagName() in listOf("body", "html")) {
            // Fallback: use the single best IoU match instead of LCA
            logger.debug("LCA for {} is <{}>, using best IoU match instead", elementType, containerRoot.tagName())
            val bestMatch = sortedCandidates[0]
            val cssSelector = cssSelectorConstructionService.constructCssSelector(bestMatch.element)
            return MappedElement(dataId = bestMatch.dsId, cssSelector = cssSelector, label = label)
        }

        val dsId = containerRoot.attr("data-ds-id")
        if (dsId.isEmpty()) {
            // Fallback: use the single best IoU match
            val bestMatch = sortedCandidates[0]
            logger.debug("LCA for {} has no data-ds-id, using best IoU match: {}", elementType, bestMatch.dsId)
            val cssSelector = cssSelectorConstructionService.constructCssSelector(bestMatch.element)
            return MappedElement(dataId = bestMatch.dsId, cssSelector = cssSelector, label = label)
        }

        logger.debug(
            "Visual {} container root: {} (tag: <{}>, IoU candidates: {}, best IoU: {})",
            elementType, dsId, containerRoot.tagName(), candidates.size, "%.3f".format(sortedCandidates[0].iou)
        )

        val cssSelector = cssSelectorConstructionService.constructCssSelector(containerRoot)
        return MappedElement(dataId = dsId, cssSelector = cssSelector, label = label)
    }

    /**
     * Calculate IoU (Intersection over Union) between an element's bounding box and the target region.
     * IoU = Intersection Area / Union Area
     * Range: [0, 1] where 1 means perfect overlap
     */
    private fun calculateIoU(
        bbox: IBrowserPage.BoundingBox,
        targetTop: Double,
        targetLeft: Double,
        targetBottom: Double,
        targetRight: Double,
        targetArea: Double
    ): Double {
        // Calculate intersection
        val interLeft = maxOf(bbox.left, targetLeft)
        val interTop = maxOf(bbox.top, targetTop)
        val interRight = minOf(bbox.right, targetRight)
        val interBottom = minOf(bbox.bottom, targetBottom)
        
        if (interLeft >= interRight || interTop >= interBottom) return 0.0
        
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val bboxArea = (bbox.right - bbox.left) * (bbox.bottom - bbox.top)
        
        if (bboxArea <= 0) return 0.0
        
        // Union = Area1 + Area2 - Intersection
        val unionArea = targetArea + bboxArea - interArea
        
        return if (unionArea > 0) interArea / unionArea else 0.0
    }

    /**
     * Find the Lowest Common Ancestor (LCA) of a list of elements.
     * The LCA is the deepest element in the DOM tree that is an ancestor of all given elements.
     */
    private fun findLowestCommonAncestor(elements: List<org.jsoup.nodes.Element>): org.jsoup.nodes.Element {
        if (elements.isEmpty()) throw IllegalArgumentException("Cannot find LCA of empty list")
        if (elements.size == 1) return elements[0]

        // Get ancestor chains for each element (including the element itself)
        val ancestorChains = elements.map { element ->
            val chain = mutableListOf<org.jsoup.nodes.Element>()
            var current: org.jsoup.nodes.Element? = element
            while (current != null) {
                chain.add(current)
                current = current.parent()
            }
            chain.reversed() // Root to leaf order
        }

        // Find the deepest common ancestor by walking from root
        var lca: org.jsoup.nodes.Element = ancestorChains[0][0] // Start with root
        val minDepth = ancestorChains.minOf { it.size }

        for (depth in 0 until minDepth) {
            val candidateAncestor = ancestorChains[0][depth]
            val allMatch = ancestorChains.all { chain -> 
                chain.size > depth && chain[depth] == candidateAncestor 
            }
            if (allMatch) {
                lca = candidateAncestor
            } else {
                break // Divergence found, LCA is the previous level
            }
        }

        return lca
    }

    // ========== Semantic HTML Detection (Primary) ==========
    // Semantic HTML elements take priority over vision detection.
    // Vision is only used as a fallback for non-semantic websites.

    private fun applySemanticOverrides(
        mapped: Map<String, MappedElement?>,
        doc: Document
    ): Map<String, MappedElement?> {
        val result = mapped.toMutableMap()

        // Semantic <header> overrides vision detection (more reliable)
            doc.selectFirst("header[data-ds-id]")?.let { element ->
                val dataId = element.attr("data-ds-id")
                val cssSelector = cssSelectorConstructionService.constructCssSelector(element)
            val wasOverride = result["header"] != null
                result["header"] = MappedElement(dataId, cssSelector, "Semantic <header> element")
            if (wasOverride) {
                logger.debug("Semantic override: <header> element with id {} replaces vision result", dataId)
            } else {
                logger.debug("Semantic detection: Found <header> element with id {}", dataId)
            }
        }

        // Semantic <footer> overrides vision detection (more reliable)
            doc.selectFirst("footer[data-ds-id]")?.let { element ->
                val dataId = element.attr("data-ds-id")
                val cssSelector = cssSelectorConstructionService.constructCssSelector(element)
            val wasOverride = result["footer"] != null
                result["footer"] = MappedElement(dataId, cssSelector, "Semantic <footer> element")
            if (wasOverride) {
                logger.debug("Semantic override: <footer> element with id {} replaces vision result", dataId)
            } else {
                logger.debug("Semantic detection: Found <footer> element with id {}", dataId)
            }
        }

        return result
    }

    private data class ProgrammaticTable(
        val dataId: String,
        val description: String
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

            tables.add(ProgrammaticTable(id, description))
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
            TableIdentification(
                cssSelector = table.cssSelector,
                dataId = table.dataId,
                auxiliaryInfo = table.label, // Now contains special consideration or description
                containsMedia = table.containsMedia
            )
        }
    }

    // ========== Helper Methods ==========

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

    // ========== Hidden Container Analysis with Caching ==========

    private val cacheJson = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    /**
     * Analyze hidden containers with caching.
     * 
     * Caching is based on structural HTML hash (with data-ds-id stripped).
     * Cached results store CSS selectors which are stable across page loads.
     * 
     * @return Triple of (tables, mobileLayouts, token usage)
     */
    private suspend fun analyzeHiddenContainersWithCache(
        hiddenContainers: List<IBrowserPage.HiddenContainer>,
        fullHtmlWithIds: String,
        modelId: String
    ): Triple<List<HiddenTableResult>, List<HiddenMobileLayoutResult>, TokenUsageMetrics> = coroutineScope {
        if (hiddenContainers.isEmpty()) {
            return@coroutineScope Triple(emptyList(), emptyList(), TokenUsageMetrics.empty(modelId))
        }

        val doc = Jsoup.parse(fullHtmlWithIds)

        // Step 1: Compute structural hashes for all containers
        val containerWithHashes = hiddenContainers.map { container ->
            val structuralHash = computeStructuralHash(container.html)
            container to structuralHash
        }

        // Step 2: Batch lookup in cache
        val allHashes = containerWithHashes.map { it.second }
        val cachedResults = hiddenContainerCacheRepository.findByHashes(allHashes)
        val cachedByHash = cachedResults.associateBy { it.structuralHtmlHash.toList() }

        // Step 3: Split into cached and uncached
        val (cached, uncached) = containerWithHashes.partition { (_, hash) ->
            cachedByHash.containsKey(hash.toList())
        }

        logger.debug(
            "Hidden container cache: {} hits, {} misses out of {} containers",
            cached.size, uncached.size, hiddenContainers.size
        )

        // Step 4: Process cached results - convert CSS selectors back to data-ds-id
        val cachedTables = mutableListOf<HiddenTableResult>()
        val cachedMobileLayouts = mutableListOf<HiddenMobileLayoutResult>()

        for ((container, hash) in cached) {
            val cacheEntry = cachedByHash[hash.toList()] ?: continue

            // Parse cached tables and map CSS selectors to current data-ds-id
            val tables = cacheJson.decodeFromString<List<CachedHiddenTable>>(cacheEntry.tablesJson)
            for (table in tables) {
                val element = doc.select(table.cssSelector).firstOrNull()
                val dataId = element?.attr("data-ds-id")
                if (dataId != null && dataId.isNotEmpty()) {
                    cachedTables.add(HiddenTableResult(dataId, table.specialConsideration))
                }
            }

            // Parse cached mobile layouts and map CSS selectors to current data-ds-id
            val mobileLayouts = cacheJson.decodeFromString<List<CachedHiddenMobileLayout>>(cacheEntry.mobileLayoutsJson)
            for (layout in mobileLayouts) {
                val element = doc.select(layout.cssSelector).firstOrNull()
                val dataId = element?.attr("data-ds-id")
                if (dataId != null && dataId.isNotEmpty()) {
                    cachedMobileLayouts.add(HiddenMobileLayoutResult(dataId, layout.description))
                }
            }
        }

        // Step 5: Analyze uncached containers via LLM in parallel
        val uncachedDeferreds = uncached.map { (container, hash) ->
            async {
                val (result, usage) = analyzeHiddenContainer(container, fullHtmlWithIds, modelId)
                Triple(container, hash, result) to usage
            }
        }

        val uncachedResults = uncachedDeferreds.awaitAll()

        // Collect fresh tables and mobile layouts
        val freshTables = mutableListOf<HiddenTableResult>()
        val freshMobileLayouts = mutableListOf<HiddenMobileLayoutResult>()
        var totalUsage = TokenUsageMetrics.empty(modelId)

        val cachesToSave = mutableListOf<HiddenContainerTableCache>()

        for ((triple, usage) in uncachedResults) {
            val (container, hash, result) = triple
            totalUsage += usage

            freshTables.addAll(result.tables)
            freshMobileLayouts.addAll(result.mobileLayouts)

            // Convert data-ds-id to CSS selectors for caching
            val tablesToCache = result.tables.mapNotNull { table ->
                val element = doc.select("[data-ds-id=\"${table.id}\"]").firstOrNull()
                    ?: return@mapNotNull null
                val cssSelector = cssSelectorConstructionService.constructCssSelector(element)
                CachedHiddenTable(cssSelector, table.specialConsideration)
            }

            val mobileLayoutsToCache = result.mobileLayouts.mapNotNull { layout ->
                val element = doc.select("[data-ds-id=\"${layout.id}\"]").firstOrNull()
                    ?: return@mapNotNull null
                val cssSelector = cssSelectorConstructionService.constructCssSelector(element)
                CachedHiddenMobileLayout(cssSelector, layout.description)
            }

            cachesToSave.add(
                HiddenContainerTableCache(
                    structuralHtmlHash = hash,
                    hasTables = tablesToCache.isNotEmpty(),
                    tablesJson = cacheJson.encodeToString(tablesToCache),
                    hasMobileLayouts = mobileLayoutsToCache.isNotEmpty(),
                    mobileLayoutsJson = cacheJson.encodeToString(mobileLayoutsToCache)
                )
            )
        }

        // Step 6: Save new cache entries
        if (cachesToSave.isNotEmpty()) {
            try {
                hiddenContainerCacheRepository.batchUpsert(cachesToSave)
                logger.debug("Cached {} hidden container analysis results", cachesToSave.size)
            } catch (e: Exception) {
                logger.warn("Failed to cache hidden container results: {}", e.message)
            }
        }

        // Combine cached + fresh results
        Triple(
            cachedTables + freshTables,
            cachedMobileLayouts + freshMobileLayouts,
            totalUsage
        )
    }

    /**
     * Compute SHA-256 hash of structural HTML (with data-ds-id stripped).
     * This creates a stable key for caching that works across page loads.
     */
    private fun computeStructuralHash(html: String): ByteArray {
        // Strip data-ds-id attributes which change on every page load
        val structuralHtml = html.replace(Regex("""data-ds-id="[^"]*""""), "")
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(structuralHtml.toByteArray(Charsets.UTF_8))
    }

    /**
     * Analyze a hidden container for tables and navigation using HTML-based LLM detection.
     * Hidden containers (accordion panels, collapsed sections, mobile menus, etc.) are not visible in screenshots.
     * 
     * Returns both table identifications and navigation identifications.
     * 
     * Note: container.html already has data-ds-id attributes from the browser's injectStableIds()
     */
    private suspend fun analyzeHiddenContainer(
        container: IBrowserPage.HiddenContainer,
        fullHtmlWithIds: String,
        modelId: String
    ): Pair<HiddenContainerAnalysisResult, TokenUsageMetrics> {
        // Step 1: Extract semantic <table> elements programmatically (no LLM needed)
        val (semanticTables, htmlWithoutTables) = extractSemanticTablesFromHiddenContainer(container.html)
        
        if (semanticTables.isNotEmpty()) {
            logger.debug(
                "Hidden container '{}': extracted {} semantic <table> elements programmatically",
                container.id, semanticTables.size
            )
        }
        
        // Step 2: Clean remaining HTML for LLM analysis (CSS tables + navigations only)
        val cleanedSnippet = cleanHtml(htmlWithoutTables)

        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<HiddenContainerAnalysisResponse>(this@VisualIdentificationAgentGenAiImpl::class.simpleName!! + "_hidden") {
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(Part.fromText(cleanedSnippet))),
                    GenerateContentConfig.builder()
                        .temperature(0F)
                        .responseSchema(hiddenContainerAnalysisSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingBudget(0)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(hiddenContainerAnalysisInstruction)))
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

        logger.debug(
            "Hidden container '{}' LLM analysis: {} CSS tables, {} mobile layouts",
            container.id, response.tables.size, response.mobileLayouts.size
        )

        // Validate that IDs exist in the full HTML
        val doc = Jsoup.parse(fullHtmlWithIds)
        val validatedLlmTables = response.tables.filter { table ->
            doc.select("[data-ds-id=\"${table.id}\"]").isNotEmpty()
        }
        val validatedMobileLayouts = response.mobileLayouts.filter { layout ->
            doc.select("[data-ds-id=\"${layout.id}\"]").isNotEmpty()
        }
        
        // Step 3: Merge semantic tables (programmatic) with CSS tables (LLM-detected)
        val allTables = semanticTables + validatedLlmTables

        val analysisResult = HiddenContainerAnalysisResult(
            containerId = container.id,
            tables = allTables,
            mobileLayouts = validatedMobileLayouts
        )

        return analysisResult to tokenUsage
    }

    /**
     * Extract semantic <table> elements from hidden container HTML.
     * Returns the table IDs and the modified HTML with tables removed.
     * 
     * Semantic tables are handled programmatically - no need for LLM analysis.
     */
    private fun extractSemanticTablesFromHiddenContainer(html: String): Pair<List<HiddenTableResult>, String> {
        val doc = Jsoup.parse(html)
        val tables = mutableListOf<HiddenTableResult>()
        
        doc.select("table[data-ds-id]").forEach { element ->
            val dataId = element.attr("data-ds-id")
            tables.add(HiddenTableResult(dataId, "Semantic <table> element"))
            element.remove()
        }
        
        return tables to doc.outerHtml()
    }

    private fun cleanHtml(rawHtml: String): String {
        val doc: Document = Jsoup.parse(rawHtml)

        // Remove noise elements (note: <table> already removed by extractSemanticTablesFromHiddenContainer)
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
        // HTML already has data-ds-id attributes from browser's injectStableIds()
        val htmlWithIds = html

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

        // Apply semantic HTML overrides (header/footer take priority over vision)
        val finalSemantic = applySemanticOverrides(mappedSemantic, doc)
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
