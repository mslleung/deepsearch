package io.deepsearch.application.services

import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.services.CssSelectorReplacement
import io.deepsearch.domain.services.IBoundingBoxDerivationService
import io.deepsearch.domain.services.IHtmlToMarkdownService
import io.deepsearch.domain.services.IJsoupDomService
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
    private val jsoupDomService: IJsoupDomService,
    private val htmlToMarkdownService: IHtmlToMarkdownService,
    private val markdownFormattingService: IMarkdownFormattingService
) : IWebpageExtractionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    // ========== Data Classes for Pipeline Stages ==========

    private data class LlmResults(
        val semanticElements: SemanticElements,
        val tableIdentifications: List<TableIdentification>,
        val iconReplacements: List<CssSelectorReplacement>,
        val imageReplacements: List<CssSelectorReplacement>,
        val imageHashes: List<ByteArray>,
        /** Mapping of image numbers to original image hash IDs: {"1": "img-abc123"} */
        val imageMapping: Map<String, String>
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
     *   capturePageSnapshot() ──┬──> identifyVisualElements() (semantic + tables in 1 call)
     *   takeFullPageScreenshot()┘
     *   extractIcons() ────────────> interpretIcons()
     *   extractImages() ───────────> interpretImages()
     *   
     * ID injection runs first (~10ms), then all browser operations run in parallel.
     * The snapshot HTML contains stable data-ds-id attributes for all elements.
     *   
     * >>> BROWSER RELEASED when all browser ops complete <<<
     *   
     *   [All LLM results] ─────────> DOM processing (Jsoup)
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

            // ===== Phase 2: Browser Captures (all parallel) =====
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
            // Combined visual identification: semantic elements + tables in single LLM call
            val visualId = async {
                val snapshot = snapshotDeferred.await()
                val screenshot = screenshotDeferred.await()
                doIdentifyVisualElements(sessionId, snapshot, screenshot)
            }
            val iconRepl = pipeFrom(iconsDeferred) { doInterpretIcons(it, sessionId) }
            val imageRepl = pipeFrom(imagesDeferred) { doInterpretImages(it, sessionId, ocrLanguage) }

            // ===== Wait for Browser Release Point =====
            val snapshot: IBrowserPage.PageSnapshotWithMetadata
            val browserDuration = measureTimeMillis {
                snapshot = snapshotDeferred.await()
                screenshotDeferred.await() // Wait for screenshot before releasing browser
                iconsDeferred.await()
                imagesDeferred.await()
            }
            logger.debug("Browser captures complete in {} ms - releasing browser", browserDuration)
            webpage.close()

            // ===== Await LLM Results =====
            val llmDuration = measureTimeMillis { awaitAll(visualId, iconRepl, imageRepl) }
            val visualResult = visualId.await()
            val imageResult = imageRepl.await()
            val llmResults = LlmResults(
                semanticElements = visualResult.semanticElements,
                tableIdentifications = visualResult.tables,
                iconReplacements = iconRepl.await().replacements,
                imageReplacements = imageResult.replacements,
                imageHashes = imageResult.hashes,
                imageMapping = imageResult.imageMapping
            )
            logger.debug(
                "LLM operations complete in {} ms: {} semantic, {} tables, {} icons, {} images",
                llmDuration,
                countSemanticElements(llmResults.semanticElements),
                llmResults.tableIdentifications.size,
                llmResults.iconReplacements.size,
                llmResults.imageReplacements.size
            )

            // ===== DOM Processing (Jsoup) + table interpretation =====
            val domResult: WebpageExtractionResult
            val jsoupDuration = measureTimeMillis {
                domResult = processDom(snapshot, llmResults, sessionId)
            }
            logger.debug("Table interpretation complete in {} ms", jsoupDuration)

            result = domResult
        }

        logger.info("Webpage extraction completed in {} ms total", totalDuration)
        result
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
            "Visual identification took {} ms: {} semantic, {} tables ({} hidden containers)",
            duration,
            countSemanticElements(result.semanticElements),
            result.tables.size,
            snapshot.hiddenContainers.size
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
                icon.cssSelectors.map { CssSelectorReplacement(it, text) }
            }
            result = IconInterpretationResult(replacements)
        }
        logger.debug("Icon interpretation took {} ms, {} replacements", duration, result.replacements.size)
        return result
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
        sessionId: SessionId
    ): WebpageExtractionResult {
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

        // ===== Step 4: Interpret and replace tables =====
        // Tables use data-ds-id selectors which remain stable after semantic removal
        // Pass placeholderMap so table snippets can have placeholders resolved before LLM interpretation
        val tableReplacements = interpretTablesWithDerivedData(
            llmResults.tableIdentifications,
            snapshot.html,
            snapshot.boundingBoxes,
            jsoupDoc,
            sessionId,
            placeholderMap
        )
        // Use placeholders for tables too - this prevents markdown newlines from being escaped
        // during HTML-to-Markdown conversion. Use TABLE prefix to avoid ID collisions with media placeholders.
        val tablePlaceholders = jsoupDomService.replaceElementsWithPlaceholders(
            jsoupDoc, tableReplacements, PlaceholderPrefix.TABLE
        )
        placeholderMap.putAll(tablePlaceholders)

        // ===== Step 5: Convert HTML to Markdown =====
        // Placeholders pass through cleanly without escaping
        val rawMarkdown = htmlToMarkdownService.convert(jsoupDoc.html())

        // ===== Step 6: Replace placeholders with actual text =====
        var finalMarkdown = rawMarkdown
        placeholderMap.values.forEach { mapping ->
            finalMarkdown = finalMarkdown.replace(mapping.placeholder, mapping.text)
        }

        // ===== Step 7: Add metadata header and popup content =====
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
            // Append popup text at the end if present (dialogs, tooltips, etc.)
            if (!popupText.isNullOrBlank()) {
                appendLine()
                appendLine("---")
                appendLine("## Popup Content")
                appendLine()
                append(popupText)
            }
        }

        return WebpageExtractionResult(
            markdown = markdown,
            title = snapshot.title,
            description = snapshot.description,
            imageHashes = llmResults.imageHashes,
            imageMapping = imageMapping
        )
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
        val markdowns = tableInterpretationService.interpretTablesBatch(tableInputs, sessionId)

        // Return replacements using stable data-ds-id selectors
        return tableInputs.zip(markdowns).map { (input, markdown) ->
            CssSelectorReplacement("[data-ds-id=\"${input.tableIdentification.dataId}\"]", markdown)
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
}
