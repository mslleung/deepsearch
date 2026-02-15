package io.deepsearch.application.services

import io.deepsearch.domain.agents.ILinearizedContentConversionAgent
import io.deepsearch.domain.agents.LinearizedContentConversionInput
import io.deepsearch.domain.agents.LinearizedContentConversionOutput
import io.deepsearch.domain.agents.ISemanticTableClassificationAgent
import io.deepsearch.domain.agents.SemanticTableClassificationInput
import io.deepsearch.domain.agents.SnippetClassification
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.services.DiscoveredTable
import io.deepsearch.domain.services.IRecursiveTableDiscoveryService
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.services.CssSelectorReplacement
import io.deepsearch.domain.services.IBoundingBoxDerivationService
import io.deepsearch.domain.services.ICssSelectorConstructionService
import io.deepsearch.domain.services.IHtmlToMarkdownService
import io.deepsearch.domain.services.IJsoupDomService
import io.deepsearch.domain.services.ISemanticListConverter
import io.deepsearch.domain.services.ISemanticTableConverter
import io.deepsearch.domain.services.MediaPlaceholderMapping
import io.deepsearch.domain.services.PlaceholderPrefix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis

/**
 * Result of webpage extraction containing markdown content and metadata.
 */
data class WebpageExtractionResult(
    val markdown: String,
    val title: String?,
    val description: String?,
    val imageHashes: List<ByteArray> = emptyList(),
    /**
     * Mapping of image numbers to original image hash IDs.
     * Format: {"1": "img-abc123", "2": "img-def456"}
     * Used to resolve ![description](#img-N) references in markdown back to actual image hashes.
     */
    val imageMapping: Map<String, String> = emptyMap()
)

interface IWebpageExtractionService {
    suspend fun extractWebpage(
        webpage: IBrowserPage,
        sessionId: SessionId,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT
    ): WebpageExtractionResult
}

class WebpageExtractionService(
    private val visualIdentificationService: IVisualIdentificationService,
    private val tableInterpretationService: ITableInterpretationService,
    private val webpageIconInterpretationService: IWebpageIconInterpretationService,
    private val webpageImageTextExtractionService: IWebpageImageTextExtractionService,
    private val boundingBoxDerivationService: IBoundingBoxDerivationService,
    private val cssSelectorConstructionService: ICssSelectorConstructionService,
    private val jsoupDomService: IJsoupDomService,
    private val htmlToMarkdownService: IHtmlToMarkdownService,
    private val markdownFormattingService: IMarkdownFormattingService,
    private val recursiveTableDiscoveryService: IRecursiveTableDiscoveryService,
    private val semanticTableConverter: ISemanticTableConverter,
    private val semanticListConverter: ISemanticListConverter,
    private val semanticTableClassificationAgent: ISemanticTableClassificationAgent,
    private val linearizedContentConversionAgent: ILinearizedContentConversionAgent
) : IWebpageExtractionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    // ========== Data Classes for Pipeline Stages ==========

    private data class LlmResults(
        val semanticElements: SemanticElements,
        /** Vision-detected CSS/div-based tables (need LLM interpretation) */
        val visualTableIdentifications: List<TableIdentification>,
        val iconReplacements: List<CssSelectorReplacement>,
        val imageReplacements: List<CssSelectorReplacement>,
        val imageHashes: List<ByteArray>,
        /** Mapping of image numbers to original image hash IDs: {"1": "img-abc123"} */
        val imageMapping: Map<String, String>
    )
    
    /** Table candidate detected from hidden container using recursive spatial analysis */
    private data class HiddenTableCandidate(
        /** Local element ID (data-ds-local) within the container HTML */
        val localElementId: String,
        /** The data-ds-id attribute value of the container element (always a stable injected ID, never a CSS path). Used for mapping to snapshot DOM. */
        val containerDataId: String,
        /** Container HTML with data-ds-local attributes (for element lookup) */
        val containerHtml: String,
        val confidence: Double,
        val rowCount: Int,
        val colCount: Int,
        val depth: Int
    )
    
    /** Semantic HTML table extracted via static analysis (no LLM needed for identification) */
    data class SemanticTableData(
        /** Stable element ID (data-ds-id) */
        val dataId: String,
        /** CSS selector using data-ds-id */
        val cssSelector: String,
        /** Outer HTML of the <table> element */
        val tableHtml: String
    )
    
    /** Semantic HTML list extracted via static analysis (no LLM needed) */
    data class SemanticListData(
        /** Stable element ID (data-ds-id) */
        val dataId: String,
        /** CSS selector using data-ds-id */
        val cssSelector: String,
        /** Outer HTML of the <ul> or <ol> element */
        val listHtml: String,
        /** Whether this is an ordered list (<ol>) or unordered (<ul>) */
        val isOrdered: Boolean
    )

    // ========== Pipeline Extension for Flow Composition ==========

    /**
     * Chains a transformation onto a Deferred, creating a new pipelined async.
     * The transformation starts as soon as the upstream value is available.
     */
    private fun <T, R> CoroutineScope.pipeFrom(
        upstream: Deferred<T>,
        transform: suspend (T) -> R
    ): Deferred<R> = async { transform(upstream.await()) }

    /**
     * Converts a webpage into text for downstream LLM processing.
     * 
     * Pipelined flow with early browser release:
     * 
     *   injectStableIds() ──────────> All elements get data-ds-id attributes
     *   capturePageSnapshot() ──┬──> identifyVisualElements() (semantic + visible tables)
     *   takeFullPageScreenshot()┘
     *   extractIcons() ────────────> interpretIcons()
     *   extractImages() ───────────> interpretImages()
     *   captureHiddenContainerBoundingBoxes() ──> RecursiveTableDiscoveryService (server-side)
     *   
     * ID injection runs first (~10ms), then browser operations run in parallel.
     * captureHiddenContainerBoundingBoxes is called LAST as it may trigger re-renders.
     *   
     * >>> BROWSER RELEASED after captureHiddenContainerBoundingBoxes <<<
     *   
     *   RecursiveTableDiscoveryService recursively discovers tables within hidden containers
     *   [All LLM results + hidden tables] ─────────> DOM processing (Jsoup)
     */
    override suspend fun extractWebpage(
        webpage: IBrowserPage,
        sessionId: SessionId,
        ocrLanguage: OcrLanguage
    ): WebpageExtractionResult = coroutineScope {
        logger.debug("Starting pipelined extraction...")
        val result: WebpageExtractionResult
        val totalDuration = measureTimeMillis {
            // ===== Phase 1: Inject Stable IDs (fast, ~10ms) =====
            // All elements get data-ds-id attributes BEFORE any extraction begins.
            // This ensures consistent IDs across snapshot, icons, and images.
            val injectionResult = webpage.injectStableIds()
            logger.debug(
                "Injected stable IDs: {} elements, {} icons, {} images",
                injectionResult.elements, injectionResult.icons, injectionResult.images
            )

            // ===== Phase 2: Browser Captures (parallel except hidden bbox) =====
            // Screenshot is captured once and shared between:
            // - Visual identification (semantic + tables in single vision call)
            // - Image extraction (fallback cropping for CORS-blocked images)
            val snapshotDeferred = async { webpage.capturePageSnapshot() }
            val screenshotDeferred = async { webpage.takeFullPageScreenshot() }
            val iconsDeferred = async { webpage.extractIcons() }
            // Image extraction uses the shared screenshot for fallback (avoids duplicate screenshot capture)
            val imagesDeferred = async { 
                val screenshot = screenshotDeferred.await()
                webpage.extractImagesWithScreenshot(screenshot)
            }

            // ===== LLM Operations (pipelined from captures) =====
            // Combined visual identification: semantic elements + visible tables in single LLM call
            val visualId = async {
                val snapshot = snapshotDeferred.await()
                val screenshot = screenshotDeferred.await()
                doIdentifyVisualElements(sessionId, snapshot, screenshot)
            }
            val iconRepl = pipeFrom(iconsDeferred) { doInterpretIcons(it, sessionId) }
            val imageRepl = pipeFrom(imagesDeferred) { doInterpretImages(it, sessionId, ocrLanguage) }

            // ===== Wait for initial browser operations =====
            val snapshot: IBrowserPage.PageSnapshotWithMetadata
            var browserDuration = measureTimeMillis {
                snapshot = snapshotDeferred.await()
                screenshotDeferred.await()
                iconsDeferred.await()
                imagesDeferred.await()
            }
            logger.debug("Initial browser captures complete in {} ms", browserDuration)
            
            // ===== Phase 3: Capture hidden container bounding boxes (LAST browser op) =====
            // This must be last as it temporarily reveals hidden containers which may trigger re-renders
            // Also extracts icons from hidden containers that were skipped by extractIcons (zero dimensions)
            val hiddenBboxData: IBrowserPage.HiddenContainerBoundingBoxes
            val hiddenBboxDuration = measureTimeMillis {
                hiddenBboxData = webpage.captureHiddenContainerBoundingBoxes()
            }
            browserDuration += hiddenBboxDuration
            logger.debug(
                "Hidden container bbox capture: {} ms, {} containers, {} elements, {} hidden icons, {} hidden images",
                hiddenBboxDuration, hiddenBboxData.hiddenContainerCount, hiddenBboxData.totalElementsCaptured,
                hiddenBboxData.hiddenIcons.size, hiddenBboxData.hiddenImages.size
            )
            
            // Convert hidden icons to Icon format and start interpretation in parallel
            val hiddenIconRepl = async {
                if (hiddenBboxData.hiddenIcons.isNotEmpty()) {
                    doInterpretHiddenIcons(hiddenBboxData.hiddenIcons, sessionId)
                } else {
                    IconInterpretationResult(emptyList())
                }
            }
            
            // Process hidden images (convert to Image format for consistent handling)
            val hiddenImageRepl = async {
                if (hiddenBboxData.hiddenImages.isNotEmpty()) {
                    doInterpretHiddenImages(hiddenBboxData.hiddenImages, sessionId, ocrLanguage)
                } else {
                    ImageInterpretationResult(emptyList(), emptyList(), emptyMap())
                }
            }
            
            // ===== Browser Released =====
            webpage.close()
            logger.debug("Browser released after {} ms total browser time", browserDuration)

            // ===== Extract semantic <table> and <ul>/<ol> elements (static analysis, no LLM) =====
            // This is done early since it only needs the HTML snapshot
            val semanticTables = extractSemanticTables(snapshot.html)
            val semanticLists = extractSemanticLists(snapshot.html)
            logger.debug(
                "Static analysis: {} semantic <table> elements, {} semantic lists extracted",
                semanticTables.size, semanticLists.size
            )

            // ===== Identify hidden table candidates using RecursiveTableDiscoveryService (server-side) =====
            // Hidden tables use containerHtml + local IDs, independent of main page snapshot
            val allHiddenTableCandidates = identifyHiddenTableCandidates(hiddenBboxData)
            logger.debug("RecursiveTableDiscoveryService found {} hidden table candidates", allHiddenTableCandidates.size)

            // ===== Await LLM Results =====
            val llmDuration = measureTimeMillis { awaitAll(visualId, iconRepl, imageRepl, hiddenIconRepl, hiddenImageRepl) }
            val visualResult = visualId.await()
            val imageResult = imageRepl.await()
            val hiddenImageResult = hiddenImageRepl.await()
            
            // Merge visible and hidden icon replacements
            val visibleIconReplacements = iconRepl.await().replacements
            val hiddenIconReplacements = hiddenIconRepl.await().replacements
            val allIconReplacements = visibleIconReplacements + hiddenIconReplacements
            logger.debug(
                "Icons: {} visible + {} from hidden containers = {} total",
                visibleIconReplacements.size, hiddenIconReplacements.size, allIconReplacements.size
            )
            
            // Merge visible and hidden image replacements
            val allImageReplacements = imageResult.replacements + hiddenImageResult.replacements
            val allImageHashes = imageResult.hashes + hiddenImageResult.hashes
            val allImageMapping = imageResult.imageMapping + hiddenImageResult.imageMapping
            logger.debug(
                "Images: {} visible + {} from hidden containers = {} total",
                imageResult.replacements.size, hiddenImageResult.replacements.size, allImageReplacements.size
            )
            
            // ===== Overlap Detection =====
            // Filter out hidden content that is inside:
            // 1. Visible or semantic tables (to prevent duplicate interpretation)
            // 2. Semantic elements like footer, header, nav (to prevent extracting navigation menus)
            val visualTableDataIds = visualResult.tables.map { it.dataId }.toSet()
            val semanticTableDataIds = semanticTables.map { it.dataId }.toSet()
            
            // Collect semantic element IDs (footer, header, nav, etc.) to exclude their hidden content
            val semanticElementDataIds = buildSet {
                visualResult.semanticElements.header?.let { add(it.dataId) }
                visualResult.semanticElements.footer?.let { add(it.dataId) }
                visualResult.semanticElements.navSidebar?.let { add(it.dataId) }
                visualResult.semanticElements.breadcrumb?.let { add(it.dataId) }
                visualResult.semanticElements.cookieBanner?.let { add(it.dataId) }
                addAll(visualResult.semanticElements.adBanners.map { it.dataId })
                addAll(visualResult.semanticElements.popups.map { it.dataId })
            }
            
            val allExcludeDataIds = visualTableDataIds + semanticTableDataIds + semanticElementDataIds
            
            val hiddenTableCandidates = filterHiddenCandidatesOverlapping(
                allHiddenTableCandidates,
                allExcludeDataIds,
                snapshot.html
            )
            
            if (hiddenTableCandidates.size < allHiddenTableCandidates.size) {
                logger.debug(
                    "Overlap detection: filtered {} hidden content inside tables/semantic elements ({} -> {})",
                    allHiddenTableCandidates.size - hiddenTableCandidates.size,
                    allHiddenTableCandidates.size,
                    hiddenTableCandidates.size
                )
            }
            
            // Visual tables are CSS/div-based tables detected by vision
            // Semantic <table> elements are handled separately via programmatic conversion
            // Hidden tables are processed separately using their containerHtml (data-ds-local)
            val llmResults = LlmResults(
                semanticElements = visualResult.semanticElements,
                visualTableIdentifications = visualResult.tables, // Vision-detected CSS/div tables
                iconReplacements = allIconReplacements,  // Merged visible + hidden icons
                imageReplacements = allImageReplacements,  // Merged visible + hidden images
                imageHashes = allImageHashes,
                imageMapping = allImageMapping
            )
            logger.debug(
                "LLM operations complete in {} ms: {} semantic elements, {} visual tables, {} semantic tables, {} semantic lists, {} hidden tables, {} icons, {} images",
                llmDuration,
                countSemanticElements(llmResults.semanticElements),
                visualResult.tables.size,
                semanticTables.size,
                semanticLists.size,
                hiddenTableCandidates.size,
                llmResults.iconReplacements.size,
                llmResults.imageReplacements.size
            )

            // ===== DOM Processing (Jsoup) + table/list interpretation =====
            val domResult: WebpageExtractionResult
            val jsoupDuration = measureTimeMillis {
                domResult = processDom(snapshot, llmResults, semanticTables, semanticLists, hiddenTableCandidates, hiddenBboxData, sessionId)
            }
            logger.debug("Table/list interpretation complete in {} ms", jsoupDuration)

            result = domResult
        }

        logger.info("Webpage extraction completed in {} ms total", totalDuration)
        result
    }
    
    // ========== Hidden Table Detection ==========
    
    /**
     * Identify table candidates from hidden container bounding boxes using RecursiveTableDiscoveryService,
     * with an HTML-based fallback for containers where spatial analysis fails.
     * 
     * The two-phase approach:
     * 1. **Spatial analysis** (RecursiveTableDiscoveryService): Uses bounding boxes to detect grid patterns.
     *    This is the primary detection method and works well when bounding boxes are reliable.
     * 2. **DOM pattern fallback**: For containers where spatial analysis found nothing, scans the HTML
     *    for repeating icon patterns (e.g., rows of checkmark/cross SVGs) that indicate comparison tables.
     *    This catches cases where bounding boxes are unreliable (animations, lazy rendering).
     */
    private fun identifyHiddenTableCandidates(
        hiddenBboxData: IBrowserPage.HiddenContainerBoundingBoxes
    ): List<HiddenTableCandidate> {
        // Phase 1: Spatial analysis
        val discoveredTables = recursiveTableDiscoveryService.discoverTablesFromHiddenContainers(
            hiddenContainerData = hiddenBboxData
        )
        
        val spatialCandidates = discoveredTables.map { table ->
            logger.debug(
                "Hidden table {} (depth={}) detected: {}x{} grid (confidence: {})",
                table.localElementId, table.depth,
                table.gridResult.rowCount, table.gridResult.colCount,
                "%.2f".format(table.gridResult.confidence)
            )
            HiddenTableCandidate(
                localElementId = table.localElementId,
                containerDataId = table.containerDataId,
                containerHtml = table.containerHtml,
                confidence = table.gridResult.confidence,
                rowCount = table.gridResult.rowCount,
                colCount = table.gridResult.colCount,
                depth = table.depth
            )
        }
        
        // Phase 2: DOM pattern fallback for ALL containers (including those with spatial candidates).
        // A container may have some sections detected spatially and others missed. The pre-LLM
        // structural dedup step (deduplicateNestedCandidates) will remove overlapping candidates later.
        val spatialElementIds = spatialCandidates.map { "${it.containerDataId}::${it.localElementId}" }.toSet()
        val fallbackCandidates = hiddenBboxData.hiddenContainers
            .flatMap { container -> detectComparisonTablesFromDom(container) }
            .filter { candidate ->
                // Skip if spatial analysis already detected a candidate for this exact element
                "${candidate.containerDataId}::${candidate.localElementId}" !in spatialElementIds
            }
        
        if (fallbackCandidates.isNotEmpty()) {
            logger.debug(
                "DOM fallback detected {} additional comparison table(s) from containers missed by spatial analysis",
                fallbackCandidates.size
            )
        }
        
        return spatialCandidates + fallbackCandidates
    }
    
    /**
     * Fallback detection: scans container HTML for repeating icon patterns that indicate comparison tables.
     * 
     * Looks for consistent patterns of SVG/icon elements per row (e.g., each feature row having
     * exactly 4 checkmark/cross icons corresponding to 4 pricing tiers). Returns ALL detected
     * comparison tables within a container (a single container may hold multiple accordion sections).
     * 
     * @return List of HiddenTableCandidates for detected comparison table patterns
     */
    private fun detectComparisonTablesFromDom(
        container: IBrowserPage.HiddenContainerBoundingBoxData
    ): List<HiddenTableCandidate> {
        val doc = Jsoup.parse(container.containerHtml)
        val body = doc.body()
        
        val detectedPatterns = mutableListOf<DetectedTablePattern>()
        val usedLocalIds = mutableSetOf<String>()
        
        val candidates = body.select("[data-ds-local]")
        // Also count total SVGs to determine if this container has icon content at all
        val totalSvgs = body.select("svg").size
        if (totalSvgs < 4) return emptyList()  // Quick skip: not enough icons for a comparison table
        
        for (candidate in candidates) {
            val localId = candidate.attr("data-ds-local")
            
            // Skip elements that are descendants of already-detected table patterns
            if (usedLocalIds.any { parentId ->
                val parentEl = body.selectFirst("[data-ds-local=\"$parentId\"]")
                parentEl != null && isDescendantOf(candidate, parentEl)
            }) continue
            
            val children = candidate.children()
            if (children.size < 3) continue
            
            // Detect comparison table using TWO strategies:
            // Strategy 1: Nested SVGs - each row is a div containing multiple SVGs
            //   <div>Feature <svg>tick</svg><svg>tick</svg><svg>tick</svg><svg>tick</svg></div>
            // Strategy 2: Flat SVGs - SVGs are direct children, interspersed with text-divs
            //   <div>Feature</div><svg>tick</svg><svg>tick</svg><svg>tick</svg><svg>tick</svg>
            val pattern = detectNestedSvgPattern(children, localId)
                ?: detectFlatSvgPattern(children, localId)
            
            if (pattern != null) {
                detectedPatterns.add(pattern)
                usedLocalIds.add(localId)
            }
        }
        
        // Log containers with many SVGs but no detected patterns for debugging
        if (detectedPatterns.isEmpty() && totalSvgs >= 8) {
            val localIdCount = candidates.size
            logger.debug(
                "DOM fallback [{}]: {} SVGs but no comparison pattern detected ({} data-ds-local elements)",
                container.containerDataId, totalSvgs, localIdCount
            )
        }
        
        return detectedPatterns.map { pattern ->
            logger.debug(
                "DOM fallback [{}]: detected comparison table pattern - {} rows x {} icon columns in element [{}]",
                container.containerDataId, pattern.rowCount, pattern.colCount, pattern.localId
            )
            HiddenTableCandidate(
                localElementId = pattern.localId,
                containerDataId = container.containerDataId,
                containerHtml = container.containerHtml,
                confidence = 0.70,
                rowCount = pattern.rowCount,
                colCount = pattern.colCount,
                depth = 0
            )
        }
    }
    
    /**
     * Strategy 1: Each direct child of the parent contains multiple SVG icons.
     * E.g., `<div>Feature <svg/><svg/><svg/><svg/></div>`
     */
    private fun detectNestedSvgPattern(
        children: org.jsoup.select.Elements,
        localId: String
    ): DetectedTablePattern? {
        val iconCountsPerChild = children.map { child -> child.select("svg").size }
        val nonZeroCounts = iconCountsPerChild.filter { it >= 2 }
        if (nonZeroCounts.isEmpty()) return null
        
        val mostCommonCount = nonZeroCounts.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        val colCount = mostCommonCount.key
        val matchingRows = mostCommonCount.value
        
        if (matchingRows < 3 || matchingRows.toDouble() / children.size < 0.50) return null
        return DetectedTablePattern(localId, matchingRows, colCount)
    }
    
    /**
     * Strategy 2: SVGs are flat siblings interspersed with non-SVG elements (feature name divs).
     * Detects patterns like: `<div>Name</div><svg/><svg/><svg/><svg/><div>Name2</div><svg/><svg/><svg/><svg/>`
     * Groups children into logical "rows" bounded by non-SVG elements and counts SVGs per group.
     */
    private fun detectFlatSvgPattern(
        children: org.jsoup.select.Elements,
        localId: String
    ): DetectedTablePattern? {
        // Group children into rows: a row starts with a non-SVG element, followed by SVG elements
        val svgCountsPerRow = mutableListOf<Int>()
        var currentSvgCount = 0
        var inRow = false
        
        for (child in children) {
            val isSvg = child.tagName().equals("svg", ignoreCase = true)
            if (isSvg) {
                currentSvgCount++
                inRow = true
            } else {
                // Non-SVG element starts a new row
                if (inRow && currentSvgCount > 0) {
                    svgCountsPerRow.add(currentSvgCount)
                }
                currentSvgCount = 0
                inRow = true  // This non-SVG element starts the next row's context
            }
        }
        // Don't forget the last row
        if (inRow && currentSvgCount > 0) {
            svgCountsPerRow.add(currentSvgCount)
        }
        
        if (svgCountsPerRow.size < 3) return null
        
        // Find the most common SVG count per row (ignoring rows with < 2 SVGs)
        val validCounts = svgCountsPerRow.filter { it >= 2 }
        if (validCounts.isEmpty()) return null
        
        val mostCommon = validCounts.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        val colCount = mostCommon.key
        val matchingRows = mostCommon.value
        
        if (matchingRows < 3 || matchingRows.toDouble() / svgCountsPerRow.size < 0.50) return null
        return DetectedTablePattern(localId, matchingRows, colCount)
    }
    
    private data class DetectedTablePattern(
        val localId: String,
        val rowCount: Int,
        val colCount: Int
    )
    
    /** Check if [element] is a descendant of [ancestor] in the DOM tree. */
    private fun isDescendantOf(element: org.jsoup.nodes.Element, ancestor: org.jsoup.nodes.Element): Boolean {
        var parent = element.parent()
        while (parent != null) {
            if (parent === ancestor) return true
            parent = parent.parent()
        }
        return false
    }
    
    // ========== Browser Capture Operations ==========

    private suspend fun doIdentifyVisualElements(
        sessionId: SessionId,
        snapshot: IBrowserPage.PageSnapshotWithMetadata,
        screenshot: IBrowserPage.Screenshot
    ): VisualIdentificationResult {
        val result: VisualIdentificationResult
        val duration = measureTimeMillis {
            result = visualIdentificationService.identifyVisualElements(sessionId, snapshot, screenshot)
        }
        logger.debug(
            "Visual identification took {} ms: {} semantic, {} visible tables",
            duration,
            countSemanticElements(result.semanticElements),
            result.tables.size
        )
        return result
    }

    private data class IconInterpretationResult(
        val replacements: List<CssSelectorReplacement>
    )

    private suspend fun doInterpretIcons(
        icons: List<IBrowserPage.Icon>,
        sessionId: SessionId
    ): IconInterpretationResult {
        val result: IconInterpretationResult
        val duration = measureTimeMillis {
            val interpretedTexts = webpageIconInterpretationService.interpretIcons(icons, sessionId)
            val replacements = icons.zip(interpretedTexts).flatMap { (icon, text) ->
                icon.cssSelectors.map { CssSelectorReplacement(it, wrapIconTextAsMarkdown(text)) }
            }
            result = IconInterpretationResult(replacements)
        }
        logger.debug("Icon interpretation took {} ms, {} replacements", duration, result.replacements.size)
        return result
    }

    /**
     * Wraps icon label text in curly braces for markdown output.
     * Uses curly braces because they are markdown-safe (won't trigger links, emphasis, etc.)
     * 
     * Example: "search" -> "{search icon}", "greater than symbol" -> "{greater than symbol icon}"
     */
    private fun wrapIconTextAsMarkdown(label: String?): String? {
        return label?.let { "{$it icon}" }
    }
    
    /**
     * Interpret icons extracted from hidden containers.
     * Converts HiddenIcon (single cssSelector) to Icon format (list of selectors) for unified processing.
     */
    private suspend fun doInterpretHiddenIcons(
        hiddenIcons: List<IBrowserPage.HiddenIcon>,
        sessionId: SessionId
    ): IconInterpretationResult {
        if (hiddenIcons.isEmpty()) {
            return IconInterpretationResult(emptyList())
        }
        
        // Convert HiddenIcon to Icon format (base64 -> bytes)
        val icons = hiddenIcons.map { hidden ->
            IBrowserPage.Icon(
                bytes = java.util.Base64.getDecoder().decode(hidden.base64),
                mimeType = io.deepsearch.domain.constants.ImageMimeType.PNG,  // Hidden icons are rendered as PNG
                cssSelectors = listOf(hidden.cssSelector)
            )
        }
        
        val result: IconInterpretationResult
        val duration = measureTimeMillis {
            val interpretedTexts = webpageIconInterpretationService.interpretIcons(icons, sessionId)
            val replacements = icons.zip(interpretedTexts).flatMap { (icon, text) ->
                icon.cssSelectors.map { CssSelectorReplacement(it, wrapIconTextAsMarkdown(text)) }
            }
            result = IconInterpretationResult(replacements)
        }
        logger.debug("Hidden icon interpretation took {} ms, {} replacements", duration, result.replacements.size)
        return result
    }
    
    /**
     * Interpret images extracted from hidden containers.
     * Converts HiddenImage to WebImage format for unified processing.
     */
    private suspend fun doInterpretHiddenImages(
        hiddenImages: List<IBrowserPage.HiddenImage>,
        sessionId: SessionId,
        ocrLanguage: OcrLanguage
    ): ImageInterpretationResult {
        if (hiddenImages.isEmpty()) {
            return ImageInterpretationResult(emptyList(), emptyList(), emptyMap())
        }
        
        // Convert HiddenImage to WebImage format (base64 -> bytes)
        val webImages = hiddenImages.map { hidden ->
            IBrowserPage.WebImage(
                bytes = java.util.Base64.getDecoder().decode(hidden.base64),
                mimeType = io.deepsearch.domain.constants.ImageMimeType.fromValue(hidden.mimeType),
                cssSelectors = listOf(hidden.cssSelector)
            )
        }
        
        // Reuse the existing image interpretation logic
        return doInterpretImages(webImages, sessionId, ocrLanguage)
    }

    private data class ImageInterpretationResult(
        val replacements: List<CssSelectorReplacement>,
        val hashes: List<ByteArray>,
        /** Mapping of image numbers to original image hash IDs: {"1": "img-abc123"} */
        val imageMapping: Map<String, String>
    )

    private suspend fun doInterpretImages(
        images: List<IBrowserPage.WebImage>,
        sessionId: SessionId,
        ocrLanguage: OcrLanguage
    ): ImageInterpretationResult {
        val result: ImageInterpretationResult
        val duration = measureTimeMillis {
            val extractionResults = webpageImageTextExtractionService.extractTextFromImagesWithHashes(
                images, sessionId, ocrLanguage
            )

            // Track image number assignments for the number-to-hash mapping
            var imageCounter = 0
            val imageMapping = mutableMapOf<String, String>()

            val replacements = images.zip(extractionResults).flatMap { (image, extraction) ->
                // Only assign a number if there's extracted text (image is relevant)
                val wrappedText = if (extraction.extractedText != null) {
                    imageCounter++
                    val imageNumber = imageCounter.toString()
                    val imageId = extraction.toImageId()
                    imageMapping[imageNumber] = imageId
                    wrapImageTextAsMarkdown(extraction.extractedText, imageNumber)
                } else {
                    null
                }
                image.cssSelectors.map { CssSelectorReplacement(it, wrappedText) }
            }
            result = ImageInterpretationResult(
                replacements = replacements,
                hashes = extractionResults.map { it.imageBytesHash },
                imageMapping = imageMapping
            )
        }
        logger.debug(
            "Image interpretation took {} ms, {} replacements, {} mapped images",
            duration, result.replacements.size, result.imageMapping.size
        )
        return result
    }

    // ========== DOM Processing ==========

    private suspend fun processDom(
        snapshot: IBrowserPage.PageSnapshotWithMetadata,
        llmResults: LlmResults,
        semanticTables: List<SemanticTableData>,
        semanticLists: List<SemanticListData>,
        hiddenTableCandidates: List<HiddenTableCandidate>,
        hiddenBboxData: IBrowserPage.HiddenContainerBoundingBoxes,
        sessionId: SessionId
    ): WebpageExtractionResult = coroutineScope {
        val imageMapping = llmResults.imageMapping
        // HTML already has data-ds-id attributes from browser's injectStableIds()
        val jsoupDoc = Jsoup.parse(snapshot.html)

        // ===== Step 1: Apply media replacements with PLACEHOLDERS =====
        // Use placeholders instead of actual text to prevent markdown syntax escaping
        // during HTML-to-Markdown conversion
        val mediaReplacements = llmResults.iconReplacements + llmResults.imageReplacements
        val placeholderMap = jsoupDomService.replaceElementsWithPlaceholders(jsoupDoc, mediaReplacements)
            .toMutableMap()

        // ===== Step 2: Extract popup text before removal =====
        // Use stable data-ds-id selectors instead of position-based CSS selectors
        val popupText = llmResults.semanticElements.popups
            .map { "[data-ds-id=\"${it.dataId}\"]" }
            .let { jsoupDomService.extractElementsText(jsoupDoc, it) }
            .values
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

        // ===== Step 3: Remove semantic elements =====
        // Use stable data-ds-id selectors instead of position-based CSS selectors
        jsoupDomService.removeElements(jsoupDoc, collectSemanticDataIdSelectors(llmResults.semanticElements))

        // ===== FOUR PARALLEL CONTENT INTERPRETATION PATHS =====
        // Path 1: Visual/CSS tables - full LLM interpretation
        // Path 2: Semantic <table> - programmatic markdown + LLM classification
        // Path 3: Semantic <ul>/<ol> - programmatic markdown (no LLM)
        // Path 4: Hidden content - LLM for tables, programmatic for lists

        // Path 1: Visual/CSS tables (need LLM for interpretation)
        val visualTablesDeferred = async {
            interpretTablesWithDerivedData(
                llmResults.visualTableIdentifications,
                snapshot.html,
                snapshot.boundingBoxes,
                jsoupDoc,
                sessionId,
                placeholderMap
            )
        }

        // Path 2: Semantic <table> elements (programmatic conversion + LLM classification)
        val semanticTablesDeferred = async {
            interpretSemanticTables(semanticTables, jsoupDoc, placeholderMap)
        }

        // Path 3: Semantic <ul>/<ol> elements (programmatic conversion, no LLM)
        // Note: Lists in the main DOM are already handled by HTML-to-markdown converter.
        // We convert them explicitly here to ensure consistent formatting and enable
        // placeholder-based handling similar to tables.
        val semanticListsDeferred = async {
            interpretSemanticLists(semanticLists, jsoupDoc, placeholderMap)
        }

        // Path 4: Hidden content (LLM for complex tables, programmatic for lists)
        // Returns CSS selector replacements to insert content at original DOM position
        // Pass media replacements so icons/images inside hidden containers get resolved
        val mediaReplacementsForHidden = llmResults.iconReplacements + llmResults.imageReplacements
        val hiddenContentDeferred = async {
            if (hiddenTableCandidates.isNotEmpty()) {
                interpretHiddenContent(hiddenTableCandidates, sessionId, mediaReplacementsForHidden, snapshot.html, snapshot.boundingBoxes, hiddenBboxData)
            } else {
                emptyList()
            }
        }

        // Await all four paths
        val visibleTableReplacements = visualTablesDeferred.await()
        val semanticTableReplacements = semanticTablesDeferred.await()
        val semanticListReplacements = semanticListsDeferred.await()
        val hiddenContentReplacements = hiddenContentDeferred.await()

        // ===== Apply table and list replacements to DOM =====
        // Use placeholders for tables and lists - this prevents markdown newlines from being escaped
        // during HTML-to-Markdown conversion. Use TABLE prefix to avoid ID collisions with media placeholders.
        val allTableReplacements = visibleTableReplacements + semanticTableReplacements
        val tablePlaceholders = jsoupDomService.replaceElementsWithPlaceholders(
            jsoupDoc, allTableReplacements, PlaceholderPrefix.TABLE
        )
        placeholderMap.putAll(tablePlaceholders)
        
        // Apply list replacements (use LIST prefix for clarity)
        val listPlaceholders = jsoupDomService.replaceElementsWithPlaceholders(
            jsoupDoc, semanticListReplacements, PlaceholderPrefix.LIST
        )
        placeholderMap.putAll(listPlaceholders)
        
        // Apply hidden content replacements (accordion panels, collapsed sections)
        // injectStableIds stores a window.__dsHiddenIdMap so that
        // captureHiddenContainerBoundingBoxes can recover original ds-element-* IDs
        // even after React re-renders. This means replacement selectors should now
        // match elements in the snapshot DOM directly.
        if (hiddenContentReplacements.isNotEmpty()) {
            // Partition into found/missing for logging, but only apply found ones
            val (foundReplacements, missingReplacements) = hiddenContentReplacements.partition { replacement ->
                try {
                    jsoupDoc.selectFirst(replacement.cssSelector) != null
                } catch (e: Exception) {
                    false
                }
            }
            
            if (foundReplacements.isNotEmpty()) {
                val hiddenPlaceholders = jsoupDomService.replaceElementsWithPlaceholders(
                    jsoupDoc, foundReplacements, PlaceholderPrefix.HIDDEN
                )
                placeholderMap.putAll(hiddenPlaceholders)
                logger.debug("Hidden content: {} replacements applied in-place", foundReplacements.size)
            }
            
            // Log any replacements that still couldn't match (edge case: element truly
            // not in snapshot, or ID recovery failed). These are skipped — no body append.
            if (missingReplacements.isNotEmpty()) {
                logger.warn(
                    "Hidden content: {} of {} replacements could not match snapshot DOM (selectors: {}). " +
                    "These hidden containers will not appear in the output.",
                    missingReplacements.size, hiddenContentReplacements.size,
                    missingReplacements.joinToString(", ") { it.cssSelector }
                )
            }
        }

        // ===== Step 5: Clean up DOM before markdown conversion =====
        // Remove empty elements that would produce markdown artifacts like orphan `>` or `* `
        val cleanupStats = jsoupDomService.cleanupForMarkdownConversion(jsoupDoc)
        if (cleanupStats.emptyListItemsRemoved > 0 || cleanupStats.emptyBlockquoteChildrenRemoved > 0) {
            logger.debug(
                "Markdown pre-cleanup: {} list items, {} blockquote children, {} elements",
                cleanupStats.emptyListItemsRemoved,
                cleanupStats.emptyBlockquoteChildrenRemoved,
                cleanupStats.emptyElementsRemoved,
            )
        }

        // ===== Step 6: Convert HTML to Markdown =====
        // Placeholders pass through cleanly without escaping
        val rawMarkdown = htmlToMarkdownService.convert(jsoupDoc.html())

        // ===== Step 7: Replace placeholders with actual text =====
        var finalMarkdown = rawMarkdown
        placeholderMap.values.forEach { mapping ->
            finalMarkdown = finalMarkdown.replace(mapping.placeholder, mapping.text)
        }
        
        // ===== Step 7b: Linearize remaining comparison patterns =====
        // Some accordion content may be in the DOM (visually hidden via CSS) but NOT captured as
        // hidden containers (e.g., content panels hidden via max-height:0 instead of display:none).
        // These appear as raw "{tick icon}" / "{cross icon}" patterns in the markdown.
        // Apply column headers from nearby linearized sections to make them meaningful for RAG.
        finalMarkdown = linearizeRemainingComparisonPatterns(finalMarkdown)

        // ===== Step 8: Add metadata header and popup content =====
        // Note: Hidden content is now inserted at original DOM position via placeholders,
        // so it no longer needs to be appended as a separate section.
        val markdown = buildString {
            appendLine("URL: ${snapshot.url}")
            if (snapshot.title.isNotBlank()) {
                appendLine("Title: ${snapshot.title}")
            }
            if (!snapshot.description.isNullOrBlank()) {
                appendLine("Description: ${snapshot.description}")
            }
            appendLine()
            append(finalMarkdown)
            // Append popup text if present (dialogs, tooltips, etc.)
            if (!popupText.isNullOrBlank()) {
                appendLine()
                appendLine("---")
                appendLine("## Popup Content")
                appendLine()
                append(popupText)
            }
        }

        WebpageExtractionResult(
            markdown = markdown,
            title = snapshot.title,
            description = snapshot.description,
            imageHashes = llmResults.imageHashes,
            imageMapping = imageMapping
        )
    }
    
    /**
     * Interpret semantic HTML tables using programmatic conversion + LLM classification.
     * 
     * This is Path 2 of the three parallel interpretation paths:
     * 1. Convert HTML <table> to markdown using SemanticTableConverter (fast, no LLM)
     * 2. Classify markdown via SemanticTableClassificationAgent (lightweight LLM call)
     * 3. Build replacements based on classification
     * 
     * @param semanticTables Semantic tables extracted via static analysis
     * @param jsoupDoc The Jsoup document (for resolving placeholders)
     * @param placeholderMap Media placeholders to resolve in table HTML
     * @return CSS selector replacements for each table
     */
    private suspend fun interpretSemanticTables(
        semanticTables: List<SemanticTableData>,
        jsoupDoc: org.jsoup.nodes.Document,
        placeholderMap: Map<String, MediaPlaceholderMapping>
    ): List<CssSelectorReplacement> {
        if (semanticTables.isEmpty()) {
            return emptyList()
        }

        // Step 1: Convert all tables to markdown (fast, no LLM)
        val markdowns = semanticTables.map { table ->
            // Resolve placeholders in table HTML before conversion
            val resolvedHtml = placeholderMap.values.fold(table.tableHtml) { html, mapping ->
                html.replace(mapping.placeholder, mapping.text)
            }
            semanticTableConverter.convertToMarkdown(resolvedHtml)
        }

        // Step 2: Classify via LLM (single batch call for all tables)
        val classificationInputs = markdowns.map { markdown ->
            SemanticTableClassificationInput(markdownTable = markdown)
        }
        val classificationResult = semanticTableClassificationAgent.classifyTables(classificationInputs)
        val classifications = classificationResult.classifications

        logger.debug(
            "Semantic table classification complete: {} tables, {} TABLE, {} CARD, {} LIST, {} COOKIE, {} HIDDEN",
            semanticTables.size,
            classifications.count { it == SnippetClassification.TABLE },
            classifications.count { it == SnippetClassification.CARD },
            classifications.count { it == SnippetClassification.LIST },
            classifications.count { it == SnippetClassification.COOKIE_DECLARATION_TABLE },
            classifications.count { it == SnippetClassification.HIDDEN_MOBILE_LAYOUT }
        )

        // Step 3: Build replacements
        return semanticTables.zip(markdowns).zip(classifications).map { (pair, classification) ->
            val (table, markdown) = pair
            val replacementText = if (classification.shouldRemoveFromDom()) {
                // Remove cookie declaration tables and hidden mobile layouts
                null
            } else {
                // Keep the programmatically converted markdown
                markdown
            }
            CssSelectorReplacement(table.cssSelector, replacementText)
        }
    }

    /**
     * Interpret hidden content using their containerHtml (with local IDs).
     * Hidden content is processed independently of the main page snapshot.
     * 
     * Returns CSS selector replacements that will replace the hidden container elements
     * in the main DOM with their interpreted markdown content. This ensures hidden content
     * (accordions, collapsed sections) appears in its original DOM position rather than
     * being appended at the end.
     * 
     * For semantic elements (<table>, <ul>, <ol>), uses programmatic conversion.
     * For non-semantic grid structures (CSS/div-based), uses LLM interpretation.
     * 
     * @param hiddenCandidates Hidden content candidates to interpret
     * @param sessionId Session ID for LLM calls
     * @param mediaReplacements Icon and image replacements to apply to hidden content HTML
     *        before conversion. Hidden containers may contain icons (e.g., checkmarks in
     *        pricing tables) that need to be resolved to text like "{checkmark icon}".
     */
    private suspend fun interpretHiddenContent(
        hiddenCandidates: List<HiddenTableCandidate>,
        sessionId: SessionId,
        mediaReplacements: List<CssSelectorReplacement>,
        snapshotHtml: String,
        snapshotBoundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        hiddenBboxData: IBrowserPage.HiddenContainerBoundingBoxes
    ): List<CssSelectorReplacement> {
        // Parse snapshot DOM once for context text extraction
        val snapshotDoc = Jsoup.parse(snapshotHtml)
        
        // Build lookup from containerDataId -> containerBox for spatial context extraction
        val containerBoxLookup = hiddenBboxData.hiddenContainers.associate { it.containerDataId to it.containerBox }
        
        // Group candidates by containerDataId - multiple tables/lists may come from the same hidden container
        val byContainer = hiddenCandidates.groupBy { it.containerDataId }
        
        // Track interpreted content per replacement target element.
        // Key is a resolved data-ds-id: either the specific element's ID (when available) or
        // the container-level ID as fallback. This allows multiple candidates from the same
        // container to produce separate, precisely-targeted replacements.
        val contentByTarget = mutableMapOf<String, MutableList<String>>()
        
        // LLM candidates need resolved HTML string (not element) because we resolve placeholders to text
        data class LlmCandidate(
            val containerDataId: String,
            val candidate: HiddenTableCandidate,
            val resolvedHtml: String,
            val containsMedia: Boolean
        )
        val llmCandidates = mutableListOf<LlmCandidate>()
        
        // Pre-index media replacement selectors by data-ds-id value for efficient per-container filtering.
        // This avoids applying all 300+ page-wide media replacements to each small container HTML,
        // which would produce noisy "N of N media replacements had no matching elements" warnings.
        val mediaReplacementsByDsId = mediaReplacements.mapNotNull { replacement ->
            // Extract data-ds-id value from selectors like [data-ds-id="ds-icon-36"]
            val match = Regex("""\[data-ds-id="([^"]+)"\]""").find(replacement.cssSelector)
            match?.groupValues?.get(1)?.let { dsId -> dsId to replacement }
        }.toMap()
        
        for ((containerDataId, rawCandidates) in byContainer) {
            
            // ===== Pre-LLM structural dedup: remove candidates that are DOM descendants of others =====
            // Within the same container, spatial analysis may find overlapping candidates at different
            // DOM depths (e.g., the full accordion table AND individual sub-sections within it).
            // Keep only the shallowest ancestor that forms a complete table; discard nested fragments.
            val candidates = deduplicateNestedCandidates(rawCandidates)
            if (candidates.size < rawCandidates.size) {
                logger.debug(
                    "Pre-LLM dedup for container [{}]: {} -> {} candidates (removed {} nested fragments)",
                    containerDataId, rawCandidates.size, candidates.size, rawCandidates.size - candidates.size
                )
            }
            
            for (candidate in candidates) {
                // Parse the container HTML to find the target element
                val containerDoc = Jsoup.parse(candidate.containerHtml)
                val originalElement = containerDoc.selectFirst("[data-ds-local=\"${candidate.localElementId}\"]")
                if (originalElement == null) {
                    logger.debug("Hidden element not found: {}", candidate.localElementId)
                    continue
                }
                
                // Check for media elements BEFORE replacement (to set containsMedia flag for LLM)
                val containsMedia = originalElement.select("img, svg, i.fa, i.fas, i.far, i.fab").isNotEmpty()
                
                // Create a scoped mini-document from the element's HTML only.
                // This prevents media replacements or SVG cleanup on ancestor/sibling elements
                // from destroying the target element (which caused "Hidden element not found
                // after media replacement" failures when modifying the full container document).
                val elementDoc = Jsoup.parseBodyFragment(originalElement.outerHtml())
                
                // Filter media replacements to only those whose data-ds-id exists within this element.
                val elementDsIds = elementDoc.select("[data-ds-id]").map { it.attr("data-ds-id") }.toSet()
                val relevantReplacements = elementDsIds.mapNotNull { dsId -> mediaReplacementsByDsId[dsId] }
                
                if (relevantReplacements.size != elementDsIds.size) {
                    logger.debug(
                        "Container [{}] candidate [{}]: {} data-ds-id elements, {} have media replacements",
                        containerDataId, candidate.localElementId, elementDsIds.size, relevantReplacements.size
                    )
                }
                
                // Apply media replacements within the scoped mini-document.
                // This converts <svg data-ds-id="icon-123">...</svg> to placeholders like {{MEDIA_0}}
                val placeholderMap = jsoupDomService.replaceElementsWithPlaceholders(
                    elementDoc, relevantReplacements, PlaceholderPrefix.MEDIA
                )
                
                // Handle any remaining SVG elements not replaced by mediaReplacements.
                // Scoped to the element's subtree only.
                // Use "[icon]" fallback for SVGs without alt text to preserve their semantic presence
                // (e.g., checkmark/cross icons in comparison tables that weren't captured as hidden icons)
                elementDoc.select("svg").forEach { svg ->
                    val altText = svg.attr("aria-label").ifBlank { 
                        svg.selectFirst("title")?.text() ?: ""
                    }
                    if (altText.isNotBlank()) {
                        svg.replaceWith(org.jsoup.nodes.TextNode("{$altText icon}"))
                    } else {
                        svg.replaceWith(org.jsoup.nodes.TextNode("[icon]"))
                    }
                }
                
                // The element is the root of the mini-document body -- always present
                val element = elementDoc.body().firstElementChild()
                if (element == null) {
                    logger.debug("Empty element after media replacement: {}", candidate.localElementId)
                    continue
                }
                
                // Resolve the specific data-ds-id of this candidate element.
                // This allows us to target the replacement precisely (e.g. a specific accordion panel)
                // instead of always targeting the container (which may be a large page wrapper).
                val resolvedTargetId = element.attr("data-ds-id").takeIf { it.isNotBlank() } ?: containerDataId
                
                val tagName = element.tagName().lowercase()
                
                // Helper to resolve placeholders in markdown
                fun resolveMediaPlaceholders(markdown: String): String {
                    var resolved = markdown
                    placeholderMap.values.forEach { mapping ->
                        resolved = resolved.replace(mapping.placeholder, mapping.text)
                    }
                    return resolved
                }
                
                when (tagName) {
                    // Semantic table - use programmatic conversion
                    "table" -> {
                        val markdown = resolveMediaPlaceholders(
                            semanticTableConverter.convertToMarkdown(element.outerHtml())
                        )
                        if (markdown.isNotBlank()) {
                            contentByTarget.getOrPut(resolvedTargetId) { mutableListOf() }.add(markdown)
                            logger.debug(
                                "Hidden semantic table {} -> target [{}]: {}",
                                candidate.localElementId, resolvedTargetId, markdown.take(100)
                            )
                        }
                    }
                    // Semantic list - use programmatic conversion
                    "ul", "ol" -> {
                        val markdown = resolveMediaPlaceholders(
                            semanticListConverter.convertToMarkdown(element.outerHtml())
                        )
                        if (markdown.isNotBlank()) {
                            contentByTarget.getOrPut(resolvedTargetId) { mutableListOf() }.add(markdown)
                            logger.debug(
                                "Hidden semantic list {} -> target [{}]: {}",
                                candidate.localElementId, resolvedTargetId, markdown.take(100)
                            )
                        }
                    }
                    // Non-semantic structure - queue for LLM interpretation
                    else -> {
                        // Resolve placeholders in HTML so LLM sees "{checkmark icon}" instead of {{MEDIA_0}}
                        val resolvedHtml = resolveMediaPlaceholders(element.outerHtml())
                        llmCandidates.add(LlmCandidate(resolvedTargetId, candidate, resolvedHtml, containsMedia))
                    }
                }
            }
        }
        
        // Process non-semantic candidates via linearized content conversion agent (batched)
        // Outputs structured text (linearized rows) instead of markdown tables for better retrieval
        if (llmCandidates.isNotEmpty()) {
            // Cache visual context per container to avoid redundant spatial lookups
            val contextCache = mutableMapOf<String, String?>()
            
            val inputs = llmCandidates.map { llmCandidate ->
                // Use the actual hidden container's data-ds-id for context extraction
                // (not llmCandidate.containerDataId which is the resolved TARGET element ID)
                val actualContainerDataId = llmCandidate.candidate.containerDataId
                val visualContext = contextCache.getOrPut(actualContainerDataId) {
                    val box = containerBoxLookup[actualContainerDataId]
                    if (box != null) {
                        extractVisualContext(actualContainerDataId, box, snapshotBoundingBoxes, snapshotDoc)
                    } else {
                        logger.debug("No bounding box found for container [{}], skipping visual context", actualContainerDataId)
                        null
                    }
                }
                val auxiliaryInfo = buildString {
                    append("Hidden content (depth=${llmCandidate.candidate.depth}): ${llmCandidate.candidate.rowCount}x${llmCandidate.candidate.colCount} grid (confidence: ${"%.0f".format(llmCandidate.candidate.confidence * 100)}%)")
                    if (!visualContext.isNullOrBlank()) {
                        append("\nVisible content above: $visualContext")
                    }
                }
                LinearizedContentConversionInput(
                    html = llmCandidate.resolvedHtml,
                    auxiliaryInfo = auxiliaryInfo,
                    containsMedia = llmCandidate.containsMedia
                )
            }
            val results = linearizedContentConversionAgent.generateBatch(inputs)
            
            // ===== Post-LLM content dedup: remove outputs whose text is substantially contained in another =====
            // Group results by container, then within each container check for content overlap.
            // This catches cases where pre-LLM structural dedup missed overlaps (e.g., non-nested
            // candidates from different DOM branches that produce similar linearized text).
            data class AcceptedResult(
                val llmCandidate: LlmCandidate,
                val result: LinearizedContentConversionOutput,
                val normalizedText: String
            )
            val acceptedByContainer = mutableMapOf<String, MutableList<AcceptedResult>>()
            
            results.forEachIndexed { index, result ->
                if (result.classification.shouldRemoveFromDom()) return@forEachIndexed
                
                val llmCandidate = llmCandidates[index]
                val normalizedText = result.structuredText.lowercase().replace(Regex("\\s+"), " ").trim()
                
                if (normalizedText.isBlank()) return@forEachIndexed
                
                val containerAccepted = acceptedByContainer.getOrPut(llmCandidate.containerDataId) { mutableListOf() }
                
                // Check if this result is substantially contained within an already-accepted result
                var isSubsumed = false
                val toRemove = mutableListOf<Int>()
                
                for ((i, accepted) in containerAccepted.withIndex()) {
                    val overlapRatio = computeTextOverlap(normalizedText, accepted.normalizedText)
                    val reverseOverlapRatio = computeTextOverlap(accepted.normalizedText, normalizedText)
                    
                    if (overlapRatio > 0.80) {
                        // This result's content is mostly contained in an existing one -- skip it
                        isSubsumed = true
                        logger.debug(
                            "Post-LLM dedup: {} subsumed by {} ({:.0f}% overlap, {} vs {} chars)",
                            llmCandidate.candidate.localElementId,
                            accepted.llmCandidate.candidate.localElementId,
                            overlapRatio * 100, normalizedText.length, accepted.normalizedText.length
                        )
                        break
                    } else if (reverseOverlapRatio > 0.80) {
                        // The existing result is mostly contained in this one -- replace it
                        toRemove.add(i)
                        logger.debug(
                            "Post-LLM dedup: {} replaces {} ({:.0f}% reverse overlap, {} vs {} chars)",
                            llmCandidate.candidate.localElementId,
                            accepted.llmCandidate.candidate.localElementId,
                            reverseOverlapRatio * 100, normalizedText.length, accepted.normalizedText.length
                        )
                    }
                }
                
                if (!isSubsumed) {
                    // Remove any results that this new one subsumes (iterate in reverse to preserve indices)
                    toRemove.sortedDescending().forEach { containerAccepted.removeAt(it) }
                    containerAccepted.add(AcceptedResult(llmCandidate, result, normalizedText))
                }
            }
            
            // Add accepted results to contentByTarget
            for ((targetId, acceptedResults) in acceptedByContainer) {
                for (accepted in acceptedResults) {
                    contentByTarget.getOrPut(targetId) { mutableListOf() }.add(accepted.result.structuredText)
                    logger.debug(
                        "Hidden content {} -> target [{}] interpreted as {}: {}",
                        accepted.llmCandidate.candidate.localElementId, targetId,
                        accepted.result.classification,
                        accepted.result.structuredText.take(100)
                    )
                }
            }
        }
        
        // Build CSS selector replacements - one per container
        // Use [data-ds-id="..."] selector for reliable mapping to snapshot DOM
        // Content-hash deduplication happens at browser level (captureHiddenContainerBoundingBoxes)
        // This secondary deduplication catches any remaining duplicates after LLM interpretation
        val seenContent = mutableSetOf<String>()
        val seenContentSignatures = mutableSetOf<String>()
        
        return contentByTarget.mapNotNull { (targetId, contentList) ->
            if (contentList.isNotEmpty()) {
                val combinedMarkdown = contentList.joinToString("\n\n")
                
                // Normalize content for deduplication: strip whitespace differences
                val normalizedContent = combinedMarkdown.replace("\\s+".toRegex(), " ").trim()
                
                // Skip if we've already seen this content (duplicate navigation menus, etc.)
                if (seenContent.contains(normalizedContent)) {
                    logger.debug(
                        "Skipping duplicate hidden content [{}]: {} chars",
                        targetId, combinedMarkdown.length
                    )
                    return@mapNotNull null
                }
                
                // Also check for near-duplicates using a content signature
                // (first 100 chars + length helps catch similar content with minor variations)
                val contentSignature = "${normalizedContent.take(100)}|${normalizedContent.length}"
                if (seenContentSignatures.contains(contentSignature)) {
                    logger.debug(
                        "Skipping near-duplicate hidden content [{}]: {} chars (signature match)",
                        targetId, combinedMarkdown.length
                    )
                    return@mapNotNull null
                }
                
                seenContent.add(normalizedContent)
                seenContentSignatures.add(contentSignature)
                
                logger.debug(
                    "Hidden content [{}] replacement: {} content items, {} chars",
                    targetId, contentList.size, combinedMarkdown.length
                )
                // Use data-ds-id selector for reliable mapping to snapshot DOM
                CssSelectorReplacement("[data-ds-id=\"$targetId\"]", combinedMarkdown)
            } else {
                null
            }
        }.also { replacements ->
            logger.debug(
                "interpretHiddenContent: {} candidates -> {} replacements (selectors: {})",
                hiddenCandidates.size,
                replacements.size,
                replacements.joinToString(", ") { it.cssSelector }
            )
        }
    }

    /**
     * Deduplicate candidates within the same container by removing nested DOM descendants.
     * 
     * When spatial analysis finds overlapping candidates at different DOM depths (e.g., the
     * full accordion table AND individual sub-sections within it), this keeps only the
     * shallowest ancestor that covers the deeper fragments. This avoids sending redundant
     * overlapping HTML snippets to the LLM.
     * 
     * For candidates that are not in an ancestor-descendant relationship, all are kept.
     */
    private fun deduplicateNestedCandidates(candidates: List<HiddenTableCandidate>): List<HiddenTableCandidate> {
        if (candidates.size <= 1) return candidates
        
        // All candidates in this group share the same containerHtml, so parse once
        val containerDoc = Jsoup.parse(candidates.first().containerHtml)
        
        // Resolve each candidate's DOM element
        val candidateElements = candidates.mapNotNull { candidate ->
            val element = containerDoc.selectFirst("[data-ds-local=\"${candidate.localElementId}\"]")
            if (element != null) candidate to element else null
        }
        
        if (candidateElements.size <= 1) return candidateElements.map { it.first }
        
        // Find candidates that are subsumed by a larger ancestor candidate
        val subsumed = mutableSetOf<String>() // localElementIds to discard
        
        for ((candidateA, elemA) in candidateElements) {
            for ((candidateB, elemB) in candidateElements) {
                if (candidateA.localElementId == candidateB.localElementId) continue
                if (candidateA.localElementId in subsumed || candidateB.localElementId in subsumed) continue
                
                // Check if elemA is an ancestor of elemB (elemB is nested inside elemA)
                val bIsInsideA = elemA.selectFirst("[data-ds-local=\"${candidateB.localElementId}\"]") != null
                if (bIsInsideA) {
                    // A contains B -- keep A (the larger ancestor), discard B (the fragment)
                    subsumed.add(candidateB.localElementId)
                    logger.debug(
                        "Pre-LLM dedup: {} (depth={}, {}x{}) subsumed by {} (depth={}, {}x{})",
                        candidateB.localElementId, candidateB.depth, candidateB.rowCount, candidateB.colCount,
                        candidateA.localElementId, candidateA.depth, candidateA.rowCount, candidateA.colCount
                    )
                }
            }
        }
        
        return candidates.filter { it.localElementId !in subsumed }
    }

    /**
     * Compute what fraction of `a`'s words appear in `b`.
     * Returns a value between 0.0 and 1.0. A value of 0.85 means 85% of a's words are in b.
     * Used for post-LLM content deduplication to detect when one linearized output is a
     * subset of another.
     */
    private fun computeTextOverlap(a: String, b: String): Double {
        if (a.isBlank()) return 1.0  // empty is trivially contained
        val wordsA = a.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        if (wordsA.isEmpty()) return 1.0
        val wordsB = b.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        val overlap = wordsA.count { it in wordsB }
        return overlap.toDouble() / wordsA.size
    }

    /**
     * Wraps extracted image text as markdown image syntax.
     * Format: ![description](#img-N) where N is the sequential image number.
     * 
     * For multiline descriptions, uses a condensed single-line format.
     */
    private fun wrapImageTextAsMarkdown(text: String, imageNumber: String): String {
        // Condense multiline text to single line for markdown alt text
        val condensedText = text.replace('\n', ' ').replace("\\s+".toRegex(), " ").trim()
        return "![${condensedText}](#img-$imageNumber)"
    }

    /**
     * Interprets tables using pre-computed data derived from the page snapshot.
     * Uses Jsoup document for HTML extraction (after media placeholder replacement).
     * Resolves placeholders in table HTML snippets before sending to LLM.
     */
    private suspend fun interpretTablesWithDerivedData(
        tables: List<TableIdentification>,
        originalHtml: String,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        jsoupDoc: org.jsoup.nodes.Document,
        sessionId: SessionId,
        placeholderMap: Map<String, MediaPlaceholderMapping>
    ): List<CssSelectorReplacement> {
        if (tables.isEmpty()) {
            return emptyList()
        }

        // Derive bounding boxes for all tables from page snapshot
        val derivedDataMap = boundingBoxDerivationService.deriveElementsBoundingBoxes(
            cssSelectors = tables.map { it.cssSelector },
            html = originalHtml,
            pageBoundingBoxes = pageBoundingBoxes
        )

        // Build table interpretation inputs using:
        // - HTML from Jsoup doc using data-ds-id selectors (stable after semantic removal)
        // - Bounding boxes derived from page snapshot
        val tableInputs = tables.mapNotNull { table ->
            val derivedData = derivedDataMap[table.cssSelector]

            // Use stable data-ds-id selector (identifiers were injected earlier)
            val dataIdSelector = "[data-ds-id=\"${table.dataId}\"]"
            val tableHtmlWithPlaceholders = jsoupDomService.getElementHtml(jsoupDoc, dataIdSelector)
            if (tableHtmlWithPlaceholders == null) {
                // Table was likely removed with a semantic element - this is expected
                logger.debug("Table element not found (may have been removed with semantic element): {}", table.dataId)
                return@mapNotNull null
            }

            // Resolve placeholders to actual text for LLM interpretation
            // LLM needs to see "Search icon" or "![Product photo](#img-1)" not "{{MEDIA_0}}"
            val tableHtmlForLlm = placeholderMap.values.fold(tableHtmlWithPlaceholders) { html, mapping ->
                html.replace(mapping.placeholder, mapping.text)
            }

            // Use derived bounding boxes, or empty if not available
            val boundingBoxes = derivedData?.boundingBoxes ?: emptyMap()

            TableInterpretationInput(
                tableIdentification = table,
                tableHtml = tableHtmlForLlm,
                boundingBoxes = boundingBoxes
            )
        }

        if (tableInputs.isEmpty()) {
            return emptyList()
        }

        // Interpret all tables in batch
        val results = tableInterpretationService.interpretTablesBatch(tableInputs, sessionId)

        // Return replacements using stable data-ds-id selectors
        // COOKIE_DECLARATION_TABLE and HIDDEN_MOBILE_LAYOUT are removed (null text)
        // Other classifications keep their interpreted markdown
        return tableInputs.zip(results).map { (input, result) ->
            val replacementText = if (result.classification.shouldRemoveFromDom()) {
                // Remove these elements entirely (pass null to cause removal)
                null
            } else {
                // Keep the interpreted markdown for TABLE, CARD, LIST
                result.markdown
            }
            CssSelectorReplacement("[data-ds-id=\"${input.tableIdentification.dataId}\"]", replacementText)
        }
    }

    /**
     * Collects stable data-ds-id selectors for removing semantic elements.
     * These selectors remain valid regardless of DOM structure changes.
     */
    private fun collectSemanticDataIdSelectors(semanticElements: SemanticElements): List<String> {
        return buildList {
            semanticElements.header?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            semanticElements.footer?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            semanticElements.navSidebar?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            semanticElements.breadcrumb?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            semanticElements.cookieBanner?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            addAll(semanticElements.adBanners.map { "[data-ds-id=\"${it.dataId}\"]" })
            addAll(semanticElements.popups.map { "[data-ds-id=\"${it.dataId}\"]" })
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
    
    // ========== Semantic Table Extraction (Static Analysis) ==========
    
    /**
     * Extract semantic HTML tables from the page snapshot using static analysis.
     * 
     * This is a pure Jsoup operation - no LLM needed for identification.
     * Semantic `<table>` elements have well-defined structure and can be
     * converted to markdown programmatically.
     * 
     * @param html The page HTML with data-ds-id attributes injected
     * @return List of semantic tables with their HTML and identifiers
     */
    private fun extractSemanticTables(html: String): List<SemanticTableData> {
        val doc = Jsoup.parse(html)
        return doc.select("table[data-ds-id]").map { element ->
            val dataId = element.attr("data-ds-id")
            SemanticTableData(
                dataId = dataId,
                cssSelector = "[data-ds-id=\"$dataId\"]",
                tableHtml = element.outerHtml()
            )
        }
    }
    
    // ========== Semantic List Extraction (Static Analysis) ==========
    
    /**
     * Extract semantic HTML lists from the page snapshot using static analysis.
     * 
     * This is a pure Jsoup operation - no LLM needed for identification.
     * Semantic `<ul>` and `<ol>` elements have well-defined structure and can be
     * converted to markdown programmatically.
     * 
     * Note: Only top-level lists are extracted. Nested lists are handled as part
     * of their parent list during conversion.
     * 
     * @param html The page HTML with data-ds-id attributes injected
     * @return List of semantic lists with their HTML and identifiers
     */
    private fun extractSemanticLists(html: String): List<SemanticListData> {
        val doc = Jsoup.parse(html)
        return doc.select("ul[data-ds-id], ol[data-ds-id]")
            // Filter out nested lists - only process top-level lists
            .filter { element -> element.parents().none { it.tagName() in listOf("ul", "ol") } }
            .map { element ->
                val dataId = element.attr("data-ds-id")
                SemanticListData(
                    dataId = dataId,
                    cssSelector = "[data-ds-id=\"$dataId\"]",
                    listHtml = element.outerHtml(),
                    isOrdered = element.tagName() == "ol"
                )
            }
    }
    
    /**
     * Convert semantic HTML lists to markdown using programmatic conversion.
     * 
     * Unlike tables which need LLM classification, lists are always included
     * in the output (no cookie/hidden layout filtering needed).
     * 
     * @param semanticLists Semantic lists extracted via static analysis
     * @param jsoupDoc The Jsoup document (for resolving placeholders)
     * @param placeholderMap Media placeholders to resolve in list HTML
     * @return CSS selector replacements for each list
     */
    private fun interpretSemanticLists(
        semanticLists: List<SemanticListData>,
        jsoupDoc: org.jsoup.nodes.Document,
        placeholderMap: Map<String, MediaPlaceholderMapping>
    ): List<CssSelectorReplacement> {
        if (semanticLists.isEmpty()) {
            return emptyList()
        }

        return semanticLists.map { list ->
            // Resolve placeholders in list HTML before conversion
            val resolvedHtml = placeholderMap.values.fold(list.listHtml) { html, mapping ->
                html.replace(mapping.placeholder, mapping.text)
            }
            val markdown = semanticListConverter.convertToMarkdown(resolvedHtml)
            CssSelectorReplacement(list.cssSelector, markdown.ifBlank { null })
        }
    }
    
    // ========== Overlap Detection ==========
    
    /**
     * Filter hidden content candidates that are inside excluded elements.
     * 
     * This prevents:
     * 1. Duplicate interpretation when a hidden container (e.g., accordion) is nested inside a visible table
     * 2. Extracting navigation menus from footer, header, nav elements that will be removed anyway
     * 
     * @param hiddenCandidates All hidden content candidates
     * @param excludeDataIds Data-ds-id values of elements to exclude (tables + semantic elements like footer/header/nav)
     * @param snapshotHtml The page HTML for DOM traversal
     * @return Hidden candidates that are NOT inside any excluded element
     */
    private fun filterHiddenCandidatesOverlapping(
        hiddenCandidates: List<HiddenTableCandidate>,
        excludeDataIds: Set<String>,
        snapshotHtml: String
    ): List<HiddenTableCandidate> {
        if (excludeDataIds.isEmpty()) {
            return hiddenCandidates
        }
        
        val doc = Jsoup.parse(snapshotHtml)
        val excludeElements = excludeDataIds.mapNotNull { dataId ->
            doc.selectFirst("[data-ds-id=\"$dataId\"]")
        }
        
        return hiddenCandidates.filter { hidden ->
            // Find the hidden container in the snapshot using data-ds-id selector
            val hiddenElement = doc.selectFirst("[data-ds-id=\"${hidden.containerDataId}\"]")
            if (hiddenElement == null) {
                // Can't find it in main snapshot, keep it (it's in a hidden container)
                return@filter true
            }
            
            // Check if this hidden element is inside any excluded element (table or semantic element)
            val isInsideExcluded = excludeElements.any { excludeElement ->
                // Check if excludeElement contains hiddenElement
                excludeElement.getAllElements().contains(hiddenElement) ||
                    hiddenElement.parents().any { it == excludeElement }
            }
            
            if (isInsideExcluded) {
                logger.debug(
                    "Filtering hidden content {} - nested inside excluded element (table/semantic)",
                    hidden.localElementId
                )
            }
            
            !isInsideExcluded
        }
    }
    
    // ========== Spatial Context Extraction ==========
    
    /**
     * Use spatial analysis (bounding boxes) to find visible content positioned directly above
     * a hidden container on the rendered page. This is more robust than DOM-tree walking because
     * it reflects actual visual layout regardless of CSS Grid, flexbox reordering, or sticky
     * positioning.
     *
     * The returned text is passed as context to the LLM, which decides whether it contains
     * column headers. If no headers are found, the LLM outputs unlabeled values.
     *
     * @param containerDataId The data-ds-id of the hidden container
     * @param containerBox Bounding box of the container (from revealed-state capture)
     * @param snapshotBoundingBoxes Bounding boxes of all visible elements (from pre-reveal capture)
     * @param snapshotDoc Parsed snapshot HTML for text extraction
     * @return Text of visible elements above the container, or null if nothing found
     */
    private fun extractVisualContext(
        containerDataId: String,
        containerBox: IBrowserPage.BoundingBox,
        snapshotBoundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        snapshotDoc: org.jsoup.nodes.Document
    ): String? {
        // Determine a spatial reference point for finding visible content above the container.
        //
        // Strategy (in priority order):
        // 1. Pre-reveal container position (from snapshotBoundingBoxes) -- same coordinate space as all visible elements
        // 2. Nearest visible ancestor in the DOM that has a valid bounding box -- for display:none containers
        //    whose own box is {0,0,0,0} but whose parent (e.g., accordion wrapper) is visible
        // 3. Revealed-state containerBox -- last resort, may have coordinate offset from container expansion
        val referenceBox = resolveReferenceBox(containerDataId, containerBox, snapshotBoundingBoxes, snapshotDoc)
        if (referenceBox == null) {
            logger.debug("Spatial context [{}]: could not resolve a reference position, skipping", containerDataId)
            return null
        }
        
        val referenceTop = referenceBox.top
        val referenceLeft = referenceBox.left
        val referenceRight = referenceBox.right
        val containerWidth = referenceRight - referenceLeft
        if (containerWidth <= 0) {
            logger.debug("Spatial context [{}]: reference has zero width, skipping", containerDataId)
            return null
        }
        
        val minElementHeight = 15.0
        val maxElementHeight = 200.0
        // Low width ratio to catch individual column header elements (e.g., "Free", "Pro AI")
        // which are narrow (~200px) relative to the full section width (~1200px)
        val minElementWidthRatio = 0.10
        val minHorizontalOverlapRatio = 0.0  // Any horizontal overlap is fine for individual headers
        val maxResults = 10
        val maxTextLength = 800
        
        // Search zone: elements near the TOP of the reference box and slightly above it.
        // For display:none containers, the reference is the nearest visible ancestor (e.g., the
        // pricing section wrapper). Column headers are typically near the top of that section
        // or in a sticky header just above it.
        val searchDepth = 200.0
        val aboveTolerance = 100.0  // Also look slightly above the reference (sticky headers)
        val searchTop = referenceTop - aboveTolerance
        val searchBottom = referenceTop + searchDepth
        
        data class SpatialMatch(val dsId: String, val distanceFromTop: Double)
        val matches = mutableListOf<SpatialMatch>()
        
        for ((dsId, box) in snapshotBoundingBoxes) {
            if (dsId == containerDataId) continue
            
            // Element must overlap with the search zone vertically
            val elementTop = box.top
            if (elementTop < searchTop || elementTop > searchBottom) continue
            
            // Element must have reasonable height (filters page wrappers and tiny icons)
            val elementHeight = box.bottom - box.top
            if (elementHeight < minElementHeight || elementHeight > maxElementHeight) continue
            
            // Element must have reasonable width (absolute minimum to filter out tiny icons)
            val elementWidth = box.right - box.left
            if (elementWidth < containerWidth * minElementWidthRatio) continue
            
            // Horizontal overlap: element must be within or overlapping the container's horizontal bounds
            // Column header elements (e.g., "Free", "Pro AI") are individually narrow and spread across
            // the container width, so we only require that the element is within the container's bounds
            val overlapLeft = maxOf(box.left, referenceLeft)
            val overlapRight = minOf(box.right, referenceRight)
            val overlapWidth = maxOf(0.0, overlapRight - overlapLeft)
            if (overlapWidth <= 0) continue  // No horizontal overlap at all
            
            matches.add(SpatialMatch(dsId, elementTop - searchTop))
        }
        
        if (matches.isEmpty()) {
            logger.debug(
                "Spatial context [{}]: no elements found near top (searchZone=[{}, {}], {} candidates scanned)",
                containerDataId, searchTop, searchBottom, snapshotBoundingBoxes.size
            )
            return null
        }
        
        // Sort by distance from top of search zone (closest to top first)
        val sorted = matches.sortedBy { it.distanceFromTop }
        
        // Extract text from the closest matches
        val texts = sorted.take(maxResults).mapNotNull { match ->
            val element = snapshotDoc.selectFirst("[data-ds-id=\"${match.dsId}\"]") ?: return@mapNotNull null
            val text = element.text().trim()
            text.takeIf { it.length >= 3 }
        }
        
        if (texts.isEmpty()) {
            logger.debug(
                "Spatial context [{}]: {} spatial matches but no text content extracted",
                containerDataId, matches.size
            )
            return null
        }
        
        val combined = texts.joinToString(" | ")
        val result = if (combined.length > maxTextLength) combined.take(maxTextLength) else combined
        
        logger.debug(
            "Spatial context for container [{}]: found {} elements above, text ({} chars): {}",
            containerDataId, matches.size, result.length, result.take(200)
        )
        
        return result
    }
    
    /**
     * Resolve a spatial reference bounding box for the given hidden container.
     *
     * Hidden containers are often `display:none`, making their own bounding box `{0,0,0,0}`.
     * This method tries multiple strategies to find a valid reference position:
     *
     * 1. Pre-reveal box: the container's own box from snapshotBoundingBoxes (same coordinate space)
     * 2. Visible ancestor: walk up the DOM to find the nearest ancestor with a valid bounding box
     * 3. Revealed-state box: the containerBox from the hidden capture (different coordinate space)
     */
    private fun resolveReferenceBox(
        containerDataId: String,
        containerBox: IBrowserPage.BoundingBox,
        snapshotBoundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        snapshotDoc: org.jsoup.nodes.Document
    ): IBrowserPage.BoundingBox? {
        // Strategy 1: Pre-reveal container position
        val preRevealBox = snapshotBoundingBoxes[containerDataId]
        if (preRevealBox != null && (preRevealBox.bottom - preRevealBox.top) > 0) {
            logger.debug(
                "Spatial context [{}]: using pre-reveal position (top={}, w={})",
                containerDataId, preRevealBox.top, preRevealBox.right - preRevealBox.left
            )
            return preRevealBox
        }
        
        // Strategy 2: Walk up the DOM to find nearest visible ancestor with a valid bounding box.
        // This handles display:none containers inside visible accordion/tab wrappers.
        val containerElement = snapshotDoc.selectFirst("[data-ds-id=\"$containerDataId\"]")
        if (containerElement != null) {
            var ancestor = containerElement.parent()
            while (ancestor != null) {
                val ancestorId = ancestor.attr("data-ds-id")
                if (ancestorId.isNotBlank()) {
                    val ancestorBox = snapshotBoundingBoxes[ancestorId]
                    if (ancestorBox != null && (ancestorBox.bottom - ancestorBox.top) > 0
                        && (ancestorBox.right - ancestorBox.left) > 0) {
                        logger.debug(
                            "Spatial context [{}]: using visible ancestor [{}] (top={}, w={})",
                            containerDataId, ancestorId, ancestorBox.top, ancestorBox.right - ancestorBox.left
                        )
                        return ancestorBox
                    }
                }
                ancestor = ancestor.parent()
            }
        }
        
        // Strategy 3: Revealed-state container box (may have coordinate offset)
        if ((containerBox.bottom - containerBox.top) > 0 && (containerBox.right - containerBox.left) > 0) {
            logger.debug(
                "Spatial context [{}]: using revealed-state box (top={}, w={})",
                containerDataId, containerBox.top, containerBox.right - containerBox.left
            )
            return containerBox
        }
        
        logger.debug(
            "Spatial context [{}]: no valid reference position found (preReveal={}, revealedBox={})",
            containerDataId,
            if (preRevealBox != null) "top=${preRevealBox.top},h=${preRevealBox.bottom - preRevealBox.top}" else "null",
            "top=${containerBox.top},h=${containerBox.bottom - containerBox.top}"
        )
        return null
    }
    
    // ========== Post-processing: Linearize Remaining Comparison Patterns ==========
    
    /**
     * Detects raw comparison patterns in the markdown (e.g., repeated "{tick icon}" / "{cross icon}"
     * lines without column headers) and applies tier labels from nearby linearized sections.
     * 
     * This catches content that was in the DOM but not captured as hidden containers (e.g., accordion
     * panels hidden via CSS max-height:0 instead of display:none).
     * 
     * Pattern detected:
     * ```
     * FeatureName
     * {information icon}  (optional)
     * {tick icon}  
     * {tick icon}  
     * {cross icon}  
     * {tick icon}
     * ```
     * 
     * Transformed to:
     * ```
     * FeatureName: Free: ✅ - Pro AI: ✅ - Premium AI: ❌ - Enterprise AI: ✅
     * ```
     */
    private fun linearizeRemainingComparisonPatterns(markdown: String): String {
        // First, extract column headers from existing linearized content.
        // Look for patterns like "Free: ... - Pro AI: ... - Premium AI: ... - Enterprise AI: ..."
        val columnHeaders = extractColumnHeadersFromLinearizedContent(markdown) ?: return markdown
        
        // Pattern: a text line (feature name), optionally followed by {information icon},
        // then exactly N lines of {tick icon} or {cross icon} (matching the column count)
        val iconPattern = Regex("""\{(tick|cross|checkmark|check mark) icon\}""")
        val infoIconPattern = Regex("""\{information icon\}""")
        
        val lines = markdown.lines().toMutableList()
        val resultLines = mutableListOf<String>()
        var i = 0
        var patternsFixed = 0
        
        while (i < lines.size) {
            val line = lines[i].trim()
            
            // Check if this line starts a comparison pattern:
            // It should be a non-empty text line that doesn't contain icons and isn't already linearized
            if (line.isNotBlank() && 
                !iconPattern.containsMatchIn(line) &&
                !infoIconPattern.containsMatchIn(line) &&
                !line.contains("Free:") && !line.contains("Pro AI:") &&
                !line.startsWith("- ") && !line.startsWith("URL:") && !line.startsWith("Title:") &&
                !line.startsWith("#") && !line.startsWith("|") && !line.startsWith("```") &&
                !line.startsWith("[") && !line.startsWith("!") &&
                line.length > 1 && line.length < 200) {
                
                // Look ahead for icon lines
                var j = i + 1
                // Skip optional {information icon} line
                if (j < lines.size && infoIconPattern.containsMatchIn(lines[j].trim())) {
                    j++
                }
                
                // Count consecutive icon lines
                val icons = mutableListOf<String>()
                while (j < lines.size && iconPattern.containsMatchIn(lines[j].trim())) {
                    val match = iconPattern.find(lines[j].trim())
                    if (match != null) {
                        val iconType = match.groupValues[1]
                        icons.add(if (iconType == "tick" || iconType == "checkmark" || iconType == "check mark") "✅" else "❌")
                    }
                    j++
                }
                
                // If we found exactly the right number of icons, linearize
                if (icons.size == columnHeaders.size) {
                    val linearized = "$line: ${columnHeaders.zip(icons).joinToString(" - ") { (header, icon) -> "$header: $icon" }}"
                    resultLines.add(linearized)
                    i = j  // Skip past the icon lines
                    patternsFixed++
                    continue
                }
            }
            
            resultLines.add(lines[i])
            i++
        }
        
        if (patternsFixed > 0) {
            logger.debug("Post-processing: linearized {} remaining comparison patterns with headers: {}", 
                patternsFixed, columnHeaders.joinToString(", "))
        }
        
        return resultLines.joinToString("\n")
    }
    
    /**
     * Extract column headers from the markdown content using multiple strategies.
     * Tries in order:
     * 1. Markdown table headers (e.g., "| | Free | Pro AI | Premium AI | Enterprise AI |")
     * 2. Linearized content patterns (e.g., "Free: ✅ - Pro AI: ✅ - ...")
     */
    private fun extractColumnHeadersFromLinearizedContent(markdown: String): List<String>? {
        return extractHeadersFromMarkdownTable(markdown)
            ?: extractHeadersFromLinearizedRows(markdown)
    }
    
    /**
     * Strategy 1: Extract column headers from markdown table header rows.
     * Looks for table rows like "| | Free | Pro | Premium | Enterprise |" that define pricing tiers.
     */
    private fun extractHeadersFromMarkdownTable(markdown: String): List<String>? {
        // Look for markdown table header rows with 3+ columns
        val tableRowPattern = Regex("""\|([^|]+\|){3,}""")
        
        for (line in markdown.lines()) {
            if (!tableRowPattern.containsMatchIn(line)) continue
            
            val cells = line.split("|")
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.all { c -> c == '-' } }
            
            if (cells.size < 3) continue
            
            // Check if this looks like a plan tier header row (contains keywords like Free, Pro, Premium, etc.)
            val tierKeywords = setOf("free", "pro", "premium", "enterprise", "basic", "starter", "business", "team", "plus")
            val matchCount = cells.count { cell -> tierKeywords.any { keyword -> cell.lowercase().contains(keyword) } }
            
            // At least 3 cells should match tier keywords, excluding the first cell (usually "Plan Name" or empty)
            if (matchCount >= 3) {
                // Skip the first cell if it's a row label (e.g., "Plan Name")
                val headers = if (cells.first().lowercase().let { it.contains("plan") || it.contains("feature") || it.contains("name") || it.isBlank() }) {
                    cells.drop(1)
                } else {
                    cells
                }
                logger.debug("Extracted column headers from markdown table: {}", headers)
                return headers
            }
        }
        return null
    }
    
    /**
     * Strategy 2: Extract column headers from linearized content rows.
     * Looks for repeated "Label: Value - Label: Value" patterns.
     */
    private fun extractHeadersFromLinearizedRows(markdown: String): List<String>? {
        val headerExtractor = Regex("""(\w[\w\s]*?):\s""")
        val headerCounts = mutableMapOf<List<String>, Int>()
        
        for (line in markdown.lines()) {
            if (!line.contains(" - ") || !line.contains(":")) continue
            val headers = headerExtractor.findAll(line).map { it.groupValues[1].trim() }.toList()
            if (headers.size >= 3) {
                headerCounts[headers] = (headerCounts[headers] ?: 0) + 1
            }
        }
        
        val bestHeaders = headerCounts.maxByOrNull { it.value }
        if (bestHeaders == null || bestHeaders.value < 2) return null
        
        return bestHeaders.key
    }
}
