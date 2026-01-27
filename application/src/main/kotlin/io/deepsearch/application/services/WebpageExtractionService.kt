package io.deepsearch.application.services

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
    private val semanticTableClassificationAgent: ISemanticTableClassificationAgent
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
        /** Stable CSS selector to find the container in the original snapshot HTML */
        val containerLocator: String,
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
                domResult = processDom(snapshot, llmResults, semanticTables, semanticLists, hiddenTableCandidates, sessionId)
            }
            logger.debug("Table/list interpretation complete in {} ms", jsoupDuration)

            result = domResult
        }

        logger.info("Webpage extraction completed in {} ms total", totalDuration)
        result
    }
    
    // ========== Hidden Table Detection ==========
    
    /**
     * Identify table candidates from hidden container bounding boxes using RecursiveTableDiscoveryService.
     * This performs recursive DOM traversal to find tables at any depth within hidden containers.
     * 
     * Each discovered table carries its containerHtml (with local IDs) for interpretation,
     * independent of the main page snapshot HTML.
     */
    private fun identifyHiddenTableCandidates(
        hiddenBboxData: IBrowserPage.HiddenContainerBoundingBoxes
    ): List<HiddenTableCandidate> {
        val discoveredTables = recursiveTableDiscoveryService.discoverTablesFromHiddenContainers(
            hiddenContainerData = hiddenBboxData
        )
        
        return discoveredTables.map { table ->
            logger.debug(
                "Hidden table {} (depth={}) detected: {}x{} grid (confidence: {})",
                table.localElementId, table.depth,
                table.gridResult.rowCount, table.gridResult.colCount,
                "%.2f".format(table.gridResult.confidence)
            )
            HiddenTableCandidate(
                localElementId = table.localElementId,
                containerLocator = table.containerLocator,
                containerHtml = table.containerHtml,
                confidence = table.gridResult.confidence,
                rowCount = table.gridResult.rowCount,
                colCount = table.gridResult.colCount,
                depth = table.depth
            )
        }
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
                interpretHiddenContent(hiddenTableCandidates, sessionId, mediaReplacementsForHidden)
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
        // This replaces the hidden container elements in the DOM with their interpreted content,
        // preserving the original DOM position rather than appending at the end.
        val hiddenPlaceholders = jsoupDomService.replaceElementsWithPlaceholders(
            jsoupDoc, hiddenContentReplacements, PlaceholderPrefix.HIDDEN
        )
        placeholderMap.putAll(hiddenPlaceholders)

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
            "Semantic table classification complete: {} tables, {} TABLE, {} COOKIE, {} HIDDEN, {} OTHERS",
            semanticTables.size,
            classifications.count { it == SnippetClassification.TABLE },
            classifications.count { it == SnippetClassification.COOKIE_DECLARATION_TABLE },
            classifications.count { it == SnippetClassification.HIDDEN_MOBILE_LAYOUT },
            classifications.count { it == SnippetClassification.OTHERS }
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
        mediaReplacements: List<CssSelectorReplacement>
    ): List<CssSelectorReplacement> {
        // Group candidates by containerLocator - multiple tables/lists may come from the same hidden container
        val byContainer = hiddenCandidates.groupBy { it.containerLocator }
        
        // Track interpreted content per container
        val contentByContainer = mutableMapOf<String, MutableList<String>>()
        
        // LLM candidates need resolved HTML string (not element) because we resolve placeholders to text
        data class LlmCandidate(
            val containerLocator: String,
            val candidate: HiddenTableCandidate,
            val resolvedHtml: String,
            val containsMedia: Boolean
        )
        val llmCandidates = mutableListOf<LlmCandidate>()
        
        for ((containerLocator, candidates) in byContainer) {
            contentByContainer[containerLocator] = mutableListOf()
            
            for (candidate in candidates) {
                // Parse the container HTML (contains data-ds-local AND data-ds-id attributes)
                // The data-ds-id attributes allow us to apply icon/image replacements
                val containerDoc = Jsoup.parse(candidate.containerHtml)
                
                // Check for media elements BEFORE replacement (to set containsMedia flag for LLM)
                val originalElement = containerDoc.selectFirst("[data-ds-local=\"${candidate.localElementId}\"]")
                if (originalElement == null) {
                    logger.debug("Hidden element not found: {}", candidate.localElementId)
                    continue
                }
                val containsMedia = originalElement.select("img, svg, i.fa, i.fas, i.far, i.fab").isNotEmpty()
                
                // Apply media replacements to resolve icons/images inside the hidden container.
                // Icons use [data-ds-id="icon-X"] selectors which are present in the container HTML.
                // This converts <svg data-ds-id="icon-123">...</svg> to placeholders like {{MEDIA_0}}
                val placeholderMap = jsoupDomService.replaceElementsWithPlaceholders(
                    containerDoc, mediaReplacements, PlaceholderPrefix.MEDIA
                )
                
                // Handle any remaining SVG elements not replaced by mediaReplacements.
                // With the new hidden icon extraction (captured when containers are revealed),
                // most icons should now be in mediaReplacements. This fallback handles edge cases
                // like dynamically rendered icons or icons without data-ds-id.
                containerDoc.select("svg").forEach { svg ->
                    val altText = svg.attr("aria-label").ifBlank { 
                        svg.selectFirst("title")?.text() ?: ""
                    }
                    if (altText.isNotBlank()) {
                        svg.replaceWith(org.jsoup.nodes.TextNode("{$altText icon}"))
                    } else {
                        // No label found - remove the SVG to avoid [icon] fallback
                        svg.remove()
                    }
                }
                
                // Re-select the element after replacement (the DOM was modified)
                val element = containerDoc.selectFirst("[data-ds-local=\"${candidate.localElementId}\"]")
                if (element == null) {
                    logger.debug("Hidden element not found after media replacement: {}", candidate.localElementId)
                    continue
                }
                
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
                            contentByContainer[containerLocator]!!.add(markdown)
                            logger.debug(
                                "Hidden semantic table {} converted programmatically: {}",
                                candidate.localElementId, markdown.take(100)
                            )
                        }
                    }
                    // Semantic list - use programmatic conversion
                    "ul", "ol" -> {
                        val markdown = resolveMediaPlaceholders(
                            semanticListConverter.convertToMarkdown(element.outerHtml())
                        )
                        if (markdown.isNotBlank()) {
                            contentByContainer[containerLocator]!!.add(markdown)
                            logger.debug(
                                "Hidden semantic list {} converted programmatically: {}",
                                candidate.localElementId, markdown.take(100)
                            )
                        }
                    }
                    // Non-semantic structure - queue for LLM interpretation
                    else -> {
                        // Resolve placeholders in HTML so LLM sees "{checkmark icon}" instead of {{MEDIA_0}}
                        val resolvedHtml = resolveMediaPlaceholders(element.outerHtml())
                        llmCandidates.add(LlmCandidate(containerLocator, candidate, resolvedHtml, containsMedia))
                    }
                }
            }
        }
        
        // Process non-semantic candidates via LLM (batched)
        if (llmCandidates.isNotEmpty()) {
            val inputs = llmCandidates.map { llmCandidate ->
                val tableIdentification = TableIdentification(
                    cssSelector = "[data-ds-local=\"${llmCandidate.candidate.localElementId}\"]",
                    dataId = llmCandidate.candidate.localElementId,
                    auxiliaryInfo = "Hidden content (depth=${llmCandidate.candidate.depth}): ${llmCandidate.candidate.rowCount}x${llmCandidate.candidate.colCount} grid (confidence: ${"%.0f".format(llmCandidate.candidate.confidence * 100)}%)",
                    containsMedia = llmCandidate.containsMedia
                )
                TableInterpretationInput(
                    tableIdentification = tableIdentification,
                    tableHtml = llmCandidate.resolvedHtml,  // Already has media placeholders resolved
                    boundingBoxes = emptyMap()
                )
            }
            
            val results = tableInterpretationService.interpretTablesBatch(inputs, sessionId)
            
            results.forEachIndexed { index, result ->
                if (!result.classification.shouldRemoveFromDom()) {
                    val llmCandidate = llmCandidates[index]
                    contentByContainer[llmCandidate.containerLocator]!!.add(result.markdown)
                    logger.debug(
                        "Hidden content {} interpreted as {}: {}",
                        llmCandidate.candidate.localElementId, result.classification, result.markdown.take(100)
                    )
                }
            }
        }
        
        // Build CSS selector replacements - one per container
        // Content-hash deduplication happens at browser level (captureHiddenContainerBoundingBoxes)
        // This secondary deduplication catches any remaining duplicates after LLM interpretation
        val seenContent = mutableSetOf<String>()
        val seenContentSignatures = mutableSetOf<String>()
        
        return contentByContainer.mapNotNull { (containerLocator, contentList) ->
            if (contentList.isNotEmpty()) {
                val combinedMarkdown = contentList.joinToString("\n\n")
                
                // Normalize content for deduplication: strip whitespace differences
                val normalizedContent = combinedMarkdown.replace("\\s+".toRegex(), " ").trim()
                
                // Skip if we've already seen this content (duplicate navigation menus, etc.)
                if (seenContent.contains(normalizedContent)) {
                    logger.debug(
                        "Skipping duplicate hidden content [{}]: {} chars",
                        containerLocator.take(50), combinedMarkdown.length
                    )
                    return@mapNotNull null
                }
                
                // Also check for near-duplicates using a content signature
                // (first 100 chars + length helps catch similar content with minor variations)
                val contentSignature = "${normalizedContent.take(100)}|${normalizedContent.length}"
                if (seenContentSignatures.contains(contentSignature)) {
                    logger.debug(
                        "Skipping near-duplicate hidden content [{}]: {} chars (signature match)",
                        containerLocator.take(50), combinedMarkdown.length
                    )
                    return@mapNotNull null
                }
                
                seenContent.add(normalizedContent)
                seenContentSignatures.add(contentSignature)
                
                logger.debug(
                    "Hidden container [{}] replacement: {} content items, {} chars",
                    containerLocator.take(50), contentList.size, combinedMarkdown.length
                )
                CssSelectorReplacement(containerLocator, combinedMarkdown)
            } else {
                null
            }
        }
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
                // Keep the interpreted markdown for TABLE, CARD, LIST, OTHERS
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
            // Try to find the hidden container in the main snapshot using its locator
            val hiddenElement = doc.selectFirst(hidden.containerLocator)
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
}
